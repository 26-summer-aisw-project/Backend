package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CandidateAccessApiIntegrationTest {

	@LocalServerPort int port;
	@Autowired JwtTokenService tokens;
	@Autowired UserRepository users;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean ObjectStorage storage;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper json = new ObjectMapper();
	private Task6HttpAssertions responses;

	@BeforeEach
	void reset() {
		responses = new Task6HttpAssertions(json, jdbc);
		jdbc.update("DELETE FROM candidate_access_idempotency_receipts");
		jdbc.update("DELETE FROM candidate_accesses");
		jdbc.update("DELETE FROM point_ledger");
		jdbc.update("DELETE FROM point_accounts");
		jdbc.update("DELETE FROM match_candidates");
		jdbc.update("DELETE FROM report_waypoints");
		jdbc.update("DELETE FROM center_handovers");
		jdbc.update("DELETE FROM found_item_images");
		jdbc.update("DELETE FROM item_features");
		jdbc.update("DELETE FROM found_items");
		jdbc.update("DELETE FROM lost_reports");
	}

	@Test
	void missingIdempotencyKeyIsRejectedAtHttpBoundary() throws Exception {
		// Given
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task6.test", "hash"));

		// When
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
				"http://127.0.0.1:" + port + "/api/v1/lost-reports/1/candidate-accesses"))
			.header("Authorization", "Bearer " + tokens.issue(owner).value())
			.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

		// Then
		assertThat(response.statusCode()).isEqualTo(400);
		JsonNode body = json.readTree(response.body());
		assertThat(body.get("code").asString()).isEqualTo("COMMON-001");
	}

	@Test
	void firstUnlockDebitsOnceAndSameOrDifferentKeysReplayDurableAccess() throws Exception {
		// Given
		User owner = user(10);
		long reportId = report(owner.getId());
		UUID firstKey = UUID.randomUUID();

		// When
		HttpResponse<String> first = post(reportId, owner, firstKey.toString());
		HttpResponse<String> same = post(reportId, owner, firstKey.toString());
		HttpResponse<String> different = post(reportId, owner, UUID.randomUUID().toString());

		// Then
		responses.access(first, new Task6HttpAssertions.AccessExpected(reportId, 9, false));
		responses.access(same, new Task6HttpAssertions.AccessExpected(reportId, 9, true));
		responses.access(different, new Task6HttpAssertions.AccessExpected(reportId, 9, true));
		assertThat(count("candidate_accesses", reportId)).isOne();
		assertThat(count("point_ledger", reportId)).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_idempotency_receipts "
				+ "WHERE report_id = ?", Integer.class, reportId)).isEqualTo(2);
	}

	@Test
	void reusedKeyAcrossReportsConflictsAndInsufficientBalanceRollsBack() throws Exception {
		// Given
		User owner = user(10);
		long firstReport = report(owner.getId());
		long secondReport = report(owner.getId());
		UUID reused = UUID.randomUUID();
		assertThat(post(firstReport, owner, reused.toString()).statusCode()).isEqualTo(200);
		User other = user(10);
		long otherReport = report(other.getId());
		User empty = user(0);
		long emptyReport = report(empty.getId());

		// When
		HttpResponse<String> conflict = post(secondReport, owner, reused.toString());
		HttpResponse<String> crossUserConflict = post(otherReport, other, reused.toString());
		HttpResponse<String> insufficient = post(emptyReport, empty, UUID.randomUUID().toString());

		// Then
		responses.error(conflict, 409, "POINT-001");
		responses.error(crossUserConflict, 409, "POINT-001");
		responses.error(insufficient, 409, "POINT-002");
		assertThat(count("candidate_accesses", secondReport)).isZero();
		assertThat(count("candidate_accesses", emptyReport)).isZero();
		assertThat(count("point_ledger", emptyReport)).isZero();
	}

	@Test
	void malformedForeignAndClosedRequestsNeverDebit() throws Exception {
		// Given
		User owner = user(10);
		User foreign = user(10);
		long reportId = report(owner.getId());
		long closedId = report(owner.getId());
		jdbc.update("UPDATE lost_reports SET status = 'CLOSED' WHERE id = ?", closedId);

		// When / Then
		for (String malformed : new String[]{"", "not-a-uuid", UUID.randomUUID().toString().replace("4", "0")}) {
			responses.error(post(reportId, owner, malformed), 400, "COMMON-001");
		}
		responses.error(post(reportId, foreign, UUID.randomUUID().toString()), 404, "COMMON-004");
		responses.error(post(closedId, owner, UUID.randomUUID().toString()), 409, "REPORT_NOT_OPEN");
		assertThat(count("point_ledger", reportId)).isZero();
		assertThat(count("point_ledger", closedId)).isZero();
	}

	@Test
	void concurrentDifferentKeysConvergeWithoutDeadlockOrSecondDebit() throws Exception {
		// Given
		User owner = user(10);
		long reportId = report(owner.getId());
		UUID firstKey = UUID.randomUUID();
		UUID secondKey = UUID.randomUUID();
		CyclicBarrier admission = new CyclicBarrier(3);
		var executor = Executors.newVirtualThreadPerTaskExecutor();
		try {
			// When
			var first = executor.submit(() -> {
				admission.await(15, TimeUnit.SECONDS);
				return post(reportId, owner, firstKey.toString());
			});
			var second = executor.submit(() -> {
				admission.await(15, TimeUnit.SECONDS);
				return post(reportId, owner, secondKey.toString());
			});
			admission.await(15, TimeUnit.SECONDS);

			// Then
			HttpResponse<String> firstResponse = first.get(15, TimeUnit.SECONDS);
			HttpResponse<String> secondResponse = second.get(15, TimeUnit.SECONDS);
			responses.concurrentResponses(firstResponse, secondResponse);
			responses.concurrentState(new Task6HttpAssertions.ConcurrentExpected(
					reportId, owner.getId(), firstKey, secondKey));
		} finally {
			executor.close();
		}
	}

	@Test
	void unlockedCandidatesRefreshAndExposeOnlyRankedPublicProjectionWithSignedThumbnail() throws Exception {
		// Given
		User owner = user(10);
		long reportId = report(owner.getId());
		long itemId = item(owner.getId());
		item(owner.getId());
		long centerId = jdbc.queryForObject("INSERT INTO lost_centers (source_key, name, address, location, "
				+ "contact_phone, operating_hours, verification_status, is_active, is_csv_managed, created_at, updated_at) "
				+ "VALUES (?, 'Campus Center', 'private center address', "
				+ "ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography, '02-000-0000', '09-18', "
				+ "'official_verified', true, false, now(), now()) RETURNING id",
				Long.class, "task6:" + UUID.randomUUID());
		jdbc.update("UPDATE found_items SET center_id = ?, storage_method = 'HANDED_TO_CENTER', "
				+ "storage_description = NULL, handover_status = 'USER_CONFIRMED', handed_at = now(), updated_at = now() "
				+ "WHERE id = ?", centerId, itemId);
		jdbc.update("INSERT INTO center_handovers (found_item_id, center_id, status, user_confirmed_at, created_at) "
				+ "VALUES (?, ?, 'USER_CONFIRMED', now(), now())", itemId, centerId);
		jdbc.update("INSERT INTO item_features (item_id, kind, feature_value, ordinal, source, visibility, created_at) "
				+ "VALUES (?, 'COLOR', 'BLACK', 1, 'FINDER', 'CANDIDATE_VIEW', now()), "
				+ "(?, 'PUBLIC_DESCRIPTION', 'public wallet', 1, 'FINDER', 'CANDIDATE_VIEW', now()), "
				+ "(?, 'OCR_TEXT', 'PRIVATE RAW', 1, 'AI', 'MATCH_ONLY', now())", itemId, itemId, itemId);
		jdbc.update("INSERT INTO found_item_images (found_item_id, original_filename, object_key, is_current, "
				+ "analysis_generation, upload_operation_id, content_type, size_bytes, created_at) "
				+ "VALUES (?, 'private.jpg', 'private-object-key', true, 0, ?, 'image/jpeg', 3, now())",
				itemId, UUID.randomUUID());
		when(storage.head("private-object-key")).thenReturn(Optional.of(new ObjectStorage.ObjectMetadata(
				"private-object-key", "image/jpeg", 3, UUID.randomUUID(), Instant.now())));
		when(storage.presignGet(eq("private-object-key"), any())).thenAnswer(invocation ->
				new ObjectStorage.PresignedGet(URI.create("https://signed.test/read?signature=secret"),
						invocation.getArgument(1)));
		assertThat(post(reportId, owner, UUID.randomUUID().toString()).statusCode()).isEqualTo(200);

		// When
		HttpResponse<String> response = get(reportId, owner);

		// Then
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		JsonNode entry = json.readTree(response.body()).get("data").get(0);
		assertThat(entry.propertyNames()).containsExactlyInAnyOrder("candidateId", "rank", "score", "category",
				"foundDate", "thumbnailUrl", "publicFeatures", "center");
		assertThat(entry.get("candidateId").asString()).isEqualTo(Long.toString(itemId));
		assertThat(json.readTree(response.body()).get("data").get(0).get("rank").asInt()).isOne();
		assertThat(json.readTree(response.body()).get("data").get(1).get("rank").asInt()).isEqualTo(2);
		assertThat(entry.get("publicFeatures").get("color").asString()).isEqualTo("BLACK");
		assertThat(entry.get("thumbnailUrl").asString()).startsWith("https://signed.test/");
		assertThat(entry.get("center").get("handoverStatus").asString()).isEqualTo("USER_CONFIRMED");
		assertThat(entry.get("center").get("notice").asString()).isEqualTo("사용자 인계 확인, 센터 검증 전");
		assertThat(response.body()).doesNotContain("PRIVATE RAW", "private-object-key", "private.jpg",
				"finderId", "foundLocation", "scoreBreakdown");
		assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
				Boolean.class, reportId)).isFalse();
	}

	private User user(int balance) {
		User user = users.saveAndFlush(new User(UUID.randomUUID() + "@task6.test", "hash"));
		jdbc.update("INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)", user.getId(), balance);
		return user;
	}

	private long report(long ownerId) {
		Long id = jdbc.queryForObject("""
				INSERT INTO lost_reports
				    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
				     effective_search_radius_meters, radius_policy_version, center_guidance,
				     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
				VALUES (?, 'WALLET', now() - interval '2 hours', now(), 'wallet', 1000, 1000,
				        'p0-radius-v1', '[]', true, 'p0-matching-v1', 'OPEN', now() + interval '1 day', now(), now())
				RETURNING id
				""", Long.class, ownerId);
		jdbc.update("INSERT INTO report_waypoints (report_id, ordinal, location, created_at) VALUES "
				+ "(?, 1, ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography, now())", id);
		return id;
	}

	private long item(long finderId) {
		return jdbc.queryForObject("""
				INSERT INTO found_items
				    (finder_id, name, category, description, found_at, found_location, found_address,
				     found_location_detail, storage_method, storage_description, status, vision_status,
				     analysis_generation, handover_status, expired_at, created_at, updated_at)
				VALUES (?, 'wallet', 'WALLET', 'private description', now() - interval '1 hour',
				        ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography, 'private address',
				        'private detail', 'MOVED_TO_SAFE_PLACE', 'private storage', 'ACTIVE', 'FAILED', 0, 'NONE',
				        now() + interval '1 day', now(), now()) RETURNING id
				""", Long.class, finderId);
	}

	private HttpResponse<String> post(long reportId, User user, String key) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
				+ "/api/v1/lost-reports/" + reportId + "/candidate-accesses"))
				.header("Authorization", "Bearer " + tokens.issue(user).value());
		if (!key.isEmpty()) request.header("Idempotency-Key", key);
		return http.send(request.POST(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(long reportId, User user) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
				+ "/api/v1/lost-reports/" + reportId + "/candidates/unlocked"))
				.header("Authorization", "Bearer " + tokens.issue(user).value()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private int count(String table, long reportId) {
		String reference = table.equals("point_ledger") ? "reference_id" : "report_id";
		return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + reference + " = ?",
				Integer.class, reportId);
	}

}
