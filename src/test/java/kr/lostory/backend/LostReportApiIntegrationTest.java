package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.lostreport.application.LostReportLifecycleCleanupService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
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
@Import({PostgresTestContainerConfig.class, LostReportApiTestClock.Config.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LostReportApiIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
	private static final Instant BEHIND_DATABASE = Instant.parse("2000-01-01T00:00:00Z");
	private static final String BASE_REQUEST = """
			{"category":"WALLET","description":"black wallet",
			 "lostAtFrom":"2026-08-23T07:00:00Z","lostAtTo":"2026-08-23T09:00:00Z",
			 "waypoints":[
			   {"ordinal":1,"point":{"latitude":37.5665,"longitude":126.9780}},
			   {"ordinal":2,"point":{"latitude":37.5650,"longitude":126.9800}}]}
			""";

	@LocalServerPort int port;
	@Autowired JwtTokenService tokens;
	@Autowired UserRepository users;
	@Autowired FoundItemRepository foundItems;
	@Autowired JdbcTemplate jdbc;
	@Autowired LostReportApiTestClock clock;
	@Autowired LostReportLifecycleCleanupService lifecycle;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void reset() {
		clock.set(NOW);
		jdbc.update("DELETE FROM match_candidates");
		jdbc.update("DELETE FROM report_waypoints");
		jdbc.update("DELETE FROM lost_reports");
		jdbc.update("DELETE FROM found_item_vision_jobs");
		jdbc.update("DELETE FROM object_deletion_outbox");
		jdbc.update("DELETE FROM found_item_images");
		jdbc.update("DELETE FROM item_features");
		jdbc.update("DELETE FROM found_items");
		jdbc.update("DELETE FROM lost_centers WHERE source_key LIKE 'task12:%'");
	}

	@Test
	void ownerCrudKeepsSavedSnapshotAndClosesOnlyReportThroughRealHttp() throws Exception {
		User owner = user(UserRole.USER);
		String token = tokens.issue(owner).value();
		String centerId = center("snapshot-v1");
		FoundItem item = foundItems.saveAndFlush(new FoundItem(
				owner.getId(), "wallet", "WALLET", "unchanged", NOW.minusSeconds(3600),
				new BigDecimal("37.5665"), new BigDecimal("126.9780"), "address", null,
				StorageMethod.LEFT_IN_PLACE, null, null));

		HttpResponse<String> created = request("POST", "/api/v1/lost-reports", token, BASE_REQUEST);
		assertThat(created.statusCode()).isEqualTo(201);
		JsonNode createJson = json.readTree(created.body());
		assertThat(createJson.propertyNames()).containsExactlyInAnyOrder(
				"id", "status", "effectiveSearchRadiusMeters", "radiusPolicyVersion", "centerGuidance",
				"candidatesStale");
		String reportId = createJson.get("id").asString();
		assertThat(createJson.get("id").isString()).isTrue();
		assertThat(createJson.get("status").asString()).isEqualTo("OPEN");
		assertThat(createJson.get("candidatesStale").asBoolean()).isFalse();
		assertThat(createJson.get("centerGuidance").get(0).get("id").asString()).isEqualTo(centerId);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM match_candidates WHERE report_id = ?",
				Integer.class, Long.valueOf(reportId))).isOne();

		HttpResponse<String> list = get("/api/v1/lost-reports?page=1&pageSize=1&status=OPEN", token);
		assertThat(list.statusCode()).isEqualTo(200);
		JsonNode listJson = json.readTree(list.body());
		assertThat(listJson.get("data")).hasSize(1);
		assertThat(listJson.get("data").get(0).get("id").asString()).isEqualTo(reportId);
		assertThat(listJson.get("meta").get("page").asInt()).isOne();
		assertThat(listJson.get("meta").get("pageSize").asInt()).isOne();
		assertThat(listJson.get("meta").get("totalItems").asLong()).isOne();

		jdbc.update("UPDATE lost_centers SET name = 'snapshot-v2' WHERE id = ?", Long.valueOf(centerId));
		JsonNode saved = json.readTree(get("/api/v1/lost-reports/" + reportId, token).body());
		assertThat(saved.get("centerGuidance").get(0).get("name").asString()).isEqualTo("snapshot-v1");
		assertThat(List.of("lostAtFrom", "lostAtTo", "expiredAt", "createdAt", "updatedAt").stream()
				.allMatch(field -> saved.get(field).isString())).isTrue();
		assertThat(saved.get("waypoints")).hasSize(2);
		assertThat(saved.get("waypoints").get(0).get("point").get("latitude").decimalValue())
				.isEqualByComparingTo("37.5665000");

		String updateBody = """
				{"description":"updated wallet","waypoints":[
				 {"ordinal":1,"point":{"latitude":37.5665,"longitude":126.9780}}]}
				""";
		HttpResponse<String> updated = request("PATCH", "/api/v1/lost-reports/" + reportId, token, updateBody);
		assertThat(updated.statusCode()).isEqualTo(200);
		assertThat(json.readTree(updated.body()).get("candidatesStale").asBoolean()).isFalse();
		JsonNode recomputed = json.readTree(get("/api/v1/lost-reports/" + reportId, token).body());
		assertThat(recomputed.get("description").asString()).isEqualTo("updated wallet");
		assertThat(recomputed.get("waypoints")).hasSize(1);
		assertThat(recomputed.get("centerGuidance").get(0).get("name").asString()).isEqualTo("snapshot-v2");

		String foundStatus = foundItems.findById(item.getId()).orElseThrow().getStatus().name();
		HttpResponse<String> closed = request("POST", "/api/v1/lost-reports/" + reportId + ":close", token, "{}");
		assertThat(closed.statusCode()).isEqualTo(200);
		assertThat(json.readTree(closed.body()).get("status").asString()).isEqualTo("CLOSED");
		assertThat(foundItems.findById(item.getId()).orElseThrow().getStatus().name()).isEqualTo(foundStatus);
		assertThat(json.readTree(get("/api/v1/lost-reports?status=CLOSED", token).body()).get("data")).hasSize(1);
		System.out.println("TASK12_HTTP_CRUD create=201 list=200 snapshot=saved patch=200 close=200 foundItem=unchanged");
	}

	@Test
	void authOwnershipTerminalAndBoundaryFailuresUseExactErrorsThroughRealHttp() throws Exception {
		User owner = user(UserRole.USER);
		User foreign = user(UserRole.USER);
		User admin = user(UserRole.ADMIN);
		String ownerToken = tokens.issue(owner).value();
		String reportId = json.readTree(request("POST", "/api/v1/lost-reports", ownerToken, BASE_REQUEST).body())
				.get("id").asString();

		assertError(http.send(HttpRequest.newBuilder(uri("/api/v1/lost-reports/" + reportId)).GET().build(),
				HttpResponse.BodyHandlers.ofString()), 401, "COMMON-002");
		for (User outsider : new User[]{foreign, admin}) {
			String outsiderToken = tokens.issue(outsider).value();
			assertError(get("/api/v1/lost-reports/" + reportId, outsiderToken), 404, "COMMON-004");
			assertError(request("PATCH", "/api/v1/lost-reports/" + reportId, outsiderToken,
					"{\"description\":\"foreign\"}"), 404, "COMMON-004");
			assertError(request("POST", "/api/v1/lost-reports/" + reportId + ":close", outsiderToken, "{}"),
					404, "COMMON-004");
		}
		assertError(get("/api/v1/lost-reports?page=0", ownerToken), 400, "COMMON-001");
		assertError(get("/api/v1/lost-reports?pageSize=101", ownerToken), 400, "COMMON-001");
		assertError(get("/api/v1/lost-reports?status=UNKNOWN", ownerToken), 400, "COMMON-001");
		assertError(request("POST", "/api/v1/lost-reports", ownerToken,
				BASE_REQUEST.replace("\"ordinal\":1", "\"ordinal\":2")), 400, "COMMON-001");
		assertError(request("POST", "/api/v1/lost-reports", ownerToken,
				BASE_REQUEST.replace("37.5665", "91")), 400, "COMMON-001");
		assertError(request("POST", "/api/v1/lost-reports", ownerToken,
				BASE_REQUEST.replace("2026-08-23T07:00:00Z", "2026-08-24T07:00:00Z")), 400, "COMMON-001");
		assertError(request("PATCH", "/api/v1/lost-reports/" + reportId, ownerToken, "{}"),
				400, "COMMON-001");

		assertThat(request("POST", "/api/v1/lost-reports/" + reportId + ":close", ownerToken, "{}")
				.statusCode()).isEqualTo(200);
		assertError(request("PATCH", "/api/v1/lost-reports/" + reportId, ownerToken,
				"{\"description\":\"closed\"}"), 409, "REPORT_NOT_OPEN");
		assertError(request("POST", "/api/v1/lost-reports/" + reportId + ":close", ownerToken, "{}"),
				409, "REPORT_NOT_OPEN");
		System.out.println("TASK12_HTTP_AUTH missing=401 foreign-user-admin=404 invalid=400 terminal=409");
	}

	@Test
	void exactExpiryIsVisibleAndAllOpenGuardConsumersRejectMutation() throws Exception {
		User owner = user(UserRole.USER);
		String token = tokens.issue(owner).value();
		String reportId = json.readTree(request("POST", "/api/v1/lost-reports", token, BASE_REQUEST).body())
				.get("id").asString();
		Instant expiresAt = jdbc.queryForObject("SELECT expired_at FROM lost_reports WHERE id = ?",
				java.sql.Timestamp.class, Long.valueOf(reportId)).toInstant();
		clock.set(expiresAt);

		HttpResponse<String> detail = get("/api/v1/lost-reports/" + reportId, token);
		assertThat(detail.statusCode()).isEqualTo(200);
		assertThat(json.readTree(detail.body()).get("status").asString()).isEqualTo("EXPIRED");
		assertError(request("PATCH", "/api/v1/lost-reports/" + reportId, token,
				"{\"description\":\"too late\"}"), 409, "REPORT_NOT_OPEN");
		assertError(request("POST", "/api/v1/lost-reports/" + reportId + ":close", token, "{}"),
				409, "REPORT_NOT_OPEN");
		assertThatThrownBy(() -> lifecycle.requireOpen(Long.valueOf(reportId), owner.getId()))
				.isInstanceOfSatisfying(LostoryException.class,
						exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REPORT_NOT_OPEN));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM match_candidates WHERE report_id = ?",
				Integer.class, Long.valueOf(reportId))).isZero();
		System.out.println("TASK12_HTTP_EXPIRY boundary=expiredAt detail=EXPIRED patch-close-future-guard=409");
	}

	@Test
	void createUsesInjectedReportClockWhenDatabaseClockIsMoreThanTtlAhead() throws Exception {
		User owner = user(UserRole.USER);
		clock.set(BEHIND_DATABASE);

		HttpResponse<String> response = request(
				"POST", "/api/v1/lost-reports", tokens.issue(owner).value(), BASE_REQUEST);

		assertFreshResponse(response, 201);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM lost_reports WHERE reporter_id = ?",
				Integer.class, owner.getId())).isOne();
	}

	@Test
	void patchUsesSameInjectedOpenBoundaryWhileCandidatePoolKeepsDatabaseTime() throws Exception {
		User owner = user(UserRole.USER);
		String token = tokens.issue(owner).value();
		String reportId = json.readTree(request("POST", "/api/v1/lost-reports", token, BASE_REQUEST).body())
				.get("id").asString();
		jdbc.update("UPDATE lost_reports SET created_at = ?, updated_at = ?, expired_at = ? WHERE id = ?",
				Timestamp.from(BEHIND_DATABASE), Timestamp.from(BEHIND_DATABASE),
				Timestamp.from(BEHIND_DATABASE.plus(Duration.ofDays(14))), Long.valueOf(reportId));
		clock.set(BEHIND_DATABASE);

		HttpResponse<String> response = request("PATCH", "/api/v1/lost-reports/" + reportId, token,
				"{\"description\":\"clock-safe update\"}");

		assertFreshResponse(response, 200);
		assertThat(json.readTree(get("/api/v1/lost-reports/" + reportId, token).body())
				.get("description").asString()).isEqualTo("clock-safe update");
	}

	private User user(UserRole role) {
		return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "User", role));
	}

	private String center(String name) {
		Long id = jdbc.queryForObject("""
				INSERT INTO lost_centers (source_key, name, address, location, contact_phone, operating_hours,
				    verification_status, is_active, is_csv_managed, created_at, updated_at)
				VALUES (?, ?, 'address', ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
				    '02-000-0000', 'always', 'official_verified', true, false, now(), now()) RETURNING id
				""", Long.class, "task12:" + UUID.randomUUID(), name);
		return id.toString();
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json")
				.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(body.get("code").asString()).isEqualTo(code);
	}

	private void assertFreshResponse(HttpResponse<String> response, int status) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(json.readTree(response.body()).get("candidatesStale").asBoolean()).isFalse();
	}
}
