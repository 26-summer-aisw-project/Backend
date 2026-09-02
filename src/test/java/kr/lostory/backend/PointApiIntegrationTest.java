package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.point.domain.PointAccount;
import kr.lostory.backend.point.domain.PointAccountRepository;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PointApiIntegrationTest {

	@LocalServerPort int port;
	@Autowired JwtTokenService tokens;
	@Autowired UserRepository users;
	@Autowired PointAccountRepository accounts;
	@Autowired JdbcTemplate jdbc;
	private final HttpClient http = HttpClient.newHttpClient();
	private final ObjectMapper json = new ObjectMapper();

	@BeforeEach
	void resetPoints() {
		jdbc.update("DELETE FROM candidate_access_idempotency_receipts");
		jdbc.update("DELETE FROM candidate_accesses");
		jdbc.update("DELETE FROM point_ledger");
		jdbc.update("DELETE FROM point_accounts");
	}

	@Test
	void balanceReturnsCurrentUsersAccountOnly() throws Exception {
		// Given
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task6.test", "hash"));
		accounts.saveAndFlush(new PointAccount(owner.getId()));

		// When
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
				"http://127.0.0.1:" + port + "/api/v1/points/balance"))
			.header("Authorization", "Bearer " + tokens.issue(owner).value()).GET().build(),
			HttpResponse.BodyHandlers.ofString());

		// Then
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactly("balance");
		assertThat(body.get("balance").asInt()).isZero();
	}

	@Test
	void balanceIgnoresForeignSelectorAndReturnsAuthenticatedAccount() throws Exception {
		// Given
		User owner = userWithBalance(7);
		User foreign = userWithBalance(91);

		// When
		HttpResponse<String> response = get("/api/v1/points/balance?userId=" + foreign.getId(), owner);

		// Then
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json.readTree(response.body()).get("balance").asInt()).isEqualTo(7);
	}

	@Test
	void ledgerIsNewestFirstPagedAndContainsOnlyPublicSelfFields() throws Exception {
		// Given
		User owner = userWithBalance(10);
		User foreign = userWithBalance(10);
		long reportId = report(owner.getId());
		long newestId = ledger(owner.getId(), reportId, -1, "CANDIDATE_ACCESS_DEBIT", "2026-08-27T03:00:00Z");
		ledger(owner.getId(), null, 10, "SIGNUP_GRANT", "2026-08-26T03:00:00Z");
		ledger(foreign.getId(), null, 10, "SIGNUP_GRANT", "2026-08-28T03:00:00Z");

		// When
		HttpResponse<String> response = get("/api/v1/points/ledger?page=1&pageSize=1", owner);

		// Then
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("data", "meta");
		assertThat(body.get("data")).hasSize(1);
		JsonNode entry = body.get("data").get(0);
		assertThat(entry.propertyNames()).containsExactlyInAnyOrder(
				"id", "type", "amount", "referenceType", "referenceId", "createdAt");
		assertThat(entry.get("id").asString()).isEqualTo(Long.toString(newestId));
		assertThat(entry.get("referenceId").asString()).isEqualTo(Long.toString(reportId));
		assertThat(entry.toString()).doesNotContain("idempotency", "reason", "userId");
		assertThat(body.get("meta").get("totalItems").asLong()).isEqualTo(2);
	}

	@Test
	void ledgerRejectsInvalidPagination() throws Exception {
		// Given
		User owner = userWithBalance(0);

		// When / Then
		for (String query : new String[]{"page=0", "pageSize=0", "pageSize=101", "page=text"}) {
			HttpResponse<String> response = get("/api/v1/points/ledger?" + query, owner);
			assertThat(response.statusCode()).isEqualTo(400);
			assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("COMMON-001");
		}
	}

	private User userWithBalance(int balance) {
		User user = users.saveAndFlush(new User(UUID.randomUUID() + "@task6.test", "hash"));
		jdbc.update("INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)", user.getId(), balance);
		return user;
	}

	private long report(long ownerId) {
		return jdbc.queryForObject("""
				INSERT INTO lost_reports
				    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
				     effective_search_radius_meters, radius_policy_version, center_guidance,
				     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
				VALUES (?, 'WALLET', now(), now(), 'ledger reference', 500, 500, 'p0-radius-v1', '[]',
				        false, 'p0-matching-v1', 'OPEN', now() + interval '1 day', now(), now()) RETURNING id
				""", Long.class, ownerId);
	}

	private long ledger(long userId, Long reportId, int amount, String type, String createdAt) {
		if (reportId == null) {
			return jdbc.queryForObject("INSERT INTO point_ledger "
					+ "(user_id, entry_type, amount, idempotency_key, created_at) "
					+ "VALUES (?, ?, ?, ?, ?::timestamptz) RETURNING id",
					Long.class, userId, type, amount, UUID.randomUUID(), createdAt);
		}
		return jdbc.queryForObject("INSERT INTO point_ledger "
				+ "(user_id, entry_type, amount, idempotency_key, reference_type, reference_id, created_at) "
				+ "VALUES (?, ?, ?, ?, 'LOST_REPORT', ?, ?::timestamptz) RETURNING id",
				Long.class, userId, type, amount, UUID.randomUUID(), reportId, createdAt);
	}

	private HttpResponse<String> get(String path, User user) throws Exception {
		return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.header("Authorization", "Bearer " + tokens.issue(user).value()).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}
}
