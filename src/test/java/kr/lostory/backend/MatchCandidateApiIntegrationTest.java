package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.lostreport.application.MatchingPolicyStartupInvalidator;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MatchCandidateApiIntegrationTest {

	@LocalServerPort int port;
	@Autowired JwtTokenService tokens;
	@Autowired UserRepository users;
	@Autowired JdbcTemplate jdbc;
	@Autowired MatchingPolicyStartupInvalidator policyInvalidator;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		jdbc.update("DELETE FROM match_candidates");
		jdbc.update("DELETE FROM report_waypoints");
		jdbc.update("DELETE FROM lost_reports");
		jdbc.update("DELETE FROM found_item_vision_jobs");
		jdbc.update("DELETE FROM object_deletion_outbox");
		jdbc.update("DELETE FROM found_item_images");
		jdbc.update("DELETE FROM item_features");
		jdbc.update("DELETE FROM center_handovers");
		jdbc.update("DELETE FROM found_items");
	}

	@Test
	void exactBoundaryCandidateExpiresAndCannotSurvivePersistedFreshSet() throws Exception {
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		String token = tokens.issue(owner).value();
		long reportId = report(owner.getId());
		long itemId = item(owner.getId());
		assertThat(get(reportId, token).statusCode()).isEqualTo(200);
		jdbc.update("UPDATE found_items SET expired_at = clock_timestamp() WHERE id = ?", itemId);

		HttpResponse<String> response = get(reportId, token);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).get("data")).isEmpty();
		assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id = ?", String.class, itemId))
				.isEqualTo("EXPIRED");
		assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
				Boolean.class, reportId)).isFalse();
	}

	@Test
	void matcherFailureReturnsExact500AndRetainsPriorRowsAndStaleFlag() throws Exception {
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		String token = tokens.issue(owner).value();
		long reportId = report(owner.getId());
		item(owner.getId());
		assertThat(get(reportId, token).statusCode()).isEqualTo(200);
		long previousItemId = jdbc.queryForObject(
				"SELECT item_id FROM match_candidates WHERE report_id = ?", Long.class, reportId);
		jdbc.update("UPDATE lost_reports SET candidates_stale = true WHERE id = ?", reportId);
		installCandidateDeleteFailure();
		try {
			HttpResponse<String> response = get(reportId, token);

			assertThat(response.statusCode()).isEqualTo(500);
			JsonNode error = json.readTree(response.body());
			assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message");
			assertThat(error.get("code").asString()).isEqualTo("COMMON-005");
			assertThat(jdbc.queryForObject(
					"SELECT item_id FROM match_candidates WHERE report_id = ?", Long.class, reportId))
					.isEqualTo(previousItemId);
			assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
					Boolean.class, reportId)).isTrue();
		} finally {
			removeCandidateDeleteFailure();
		}
	}

	@Test
	void openReportWithoutEligibleItemsReturnsFreshEmptySet() throws Exception {
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		long reportId = report(owner.getId());

		HttpResponse<String> response = get(reportId, tokens.issue(owner).value());

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("data")).isEmpty();
		assertThat(body.get("candidatesStale").asBoolean()).isFalse();
	}

	@Test
	void closedAndExpiredReportsReturnReportNotOpen() throws Exception {
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		String token = tokens.issue(owner).value();
		long closedId = report(owner.getId());
		long expiredId = report(owner.getId());
		jdbc.update("UPDATE lost_reports SET status = 'CLOSED' WHERE id = ?", closedId);
		jdbc.update("UPDATE lost_reports SET expired_at = clock_timestamp() WHERE id = ?", expiredId);

		for (long reportId : new long[]{closedId, expiredId}) {
			HttpResponse<String> response = get(reportId, token);
			assertThat(response.statusCode()).isEqualTo(409);
			JsonNode body = json.readTree(response.body());
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
			assertThat(body.get("code").asString()).isEqualTo("REPORT_NOT_OPEN");
		}
	}

	@Test
	void startupPolicyInvalidationMarksOnlyOpenUnexpiredReportsStale() {
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		long openId = report(owner.getId());
		long closedId = report(owner.getId());
		long expiredId = report(owner.getId());
		jdbc.update("UPDATE lost_reports SET candidates_stale = false WHERE id IN (?, ?, ?)",
				openId, closedId, expiredId);
		jdbc.update("UPDATE lost_reports SET status = 'CLOSED' WHERE id = ?", closedId);
		jdbc.update("UPDATE lost_reports SET expired_at = clock_timestamp() WHERE id = ?", expiredId);

		policyInvalidator.run(null);

		assertThat(stale(openId)).isTrue();
		assertThat(stale(closedId)).isFalse();
		assertThat(stale(expiredId)).isFalse();
	}

	@Test
	void staleOwnerGetRecomputesAndReturnsOnlyScoreContractThroughRealHttp() throws Exception {
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		long reportId = report(owner.getId());
		long itemId = item(owner.getId());

		HttpResponse<String> response = get(reportId, tokens.issue(owner).value());

		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("lastMatchedAt", "candidatesStale", "data");
		assertThat(body.get("candidatesStale").asBoolean()).isFalse();
		assertThat(body.get("lastMatchedAt").isString()).isTrue();
		assertThat(body.get("data")).hasSize(1);
		assertThat(body.get("data").get(0).propertyNames())
				.containsExactlyInAnyOrder("candidateId", "rank", "score");
		assertThat(body.get("data").get(0).get("candidateId").asString()).isEqualTo(Long.toString(itemId));
		assertThat(body.get("data").get(0).get("candidateId").isString()).isTrue();
	}

	private long report(long reporterId) {
		Long id = jdbc.queryForObject("""
				INSERT INTO lost_reports
				    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
				     effective_search_radius_meters, radius_policy_version, center_guidance,
				     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
				VALUES (?, 'WALLET', clock_timestamp() - INTERVAL '2 hours', clock_timestamp(), 'black wallet',
				        1000, 1000, 'p0-radius-v1', '[]', true, 'matching-v1', 'OPEN',
				        clock_timestamp() + INTERVAL '14 days', clock_timestamp(), clock_timestamp())
				RETURNING id
				""", Long.class, reporterId);
		jdbc.update("""
				INSERT INTO report_waypoints (report_id, ordinal, location, created_at)
				VALUES (?, 1, ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography, clock_timestamp())
				""", id);
		return id;
	}

	private long item(long finderId) {
		return jdbc.queryForObject("""
				INSERT INTO found_items
				    (finder_id, name, category, description, found_at, found_location, storage_method,
				     status, vision_status, analysis_generation, handover_status, expired_at, created_at, updated_at)
				VALUES (?, 'wallet', 'WALLET', 'black wallet', clock_timestamp() - INTERVAL '1 hour',
				        ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
				        'LEFT_IN_PLACE', 'ACTIVE', 'FAILED', 0, 'NONE',
				        clock_timestamp() + INTERVAL '14 days', clock_timestamp(), clock_timestamp())
				RETURNING id
				""", Long.class, finderId);
	}

	private HttpResponse<String> get(long reportId, String token) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create(
				"http://localhost:" + port + "/api/v1/lost-reports/" + reportId + "/candidates"))
				.header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private boolean stale(long reportId) {
		return jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
				Boolean.class, reportId);
	}

	private void installCandidateDeleteFailure() {
		jdbc.execute("""
				CREATE FUNCTION fail_task14_candidate_delete() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN RAISE EXCEPTION 'forced matcher failure'; END;
				$$
				""");
		jdbc.execute("""
				CREATE TRIGGER fail_task14_candidate_delete BEFORE DELETE ON match_candidates
				FOR EACH ROW EXECUTE FUNCTION fail_task14_candidate_delete()
				""");
	}

	private void removeCandidateDeleteFailure() {
		jdbc.execute("DROP TRIGGER fail_task14_candidate_delete ON match_candidates");
		jdbc.execute("DROP FUNCTION fail_task14_candidate_delete()");
	}
}
