package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
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
class ApiContractIntegrationTest {

	@LocalServerPort int port;
	@Autowired JwtTokenService tokens;
	@Autowired UserRepository users;
	@Autowired JdbcTemplate jdbc;
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void candidateOwnershipConcealsForeignUserAndAdminWithExactError() throws Exception {
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		User foreign = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		User admin = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash", "Admin", UserRole.ADMIN));
		long reportId = jdbc.queryForObject("""
				INSERT INTO lost_reports
				    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
				     effective_search_radius_meters, radius_policy_version, center_guidance,
				     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
				VALUES (?, 'WALLET', now(), now(), 'wallet', 1000, 1000, 'p0-radius-v1', '[]', false,
				        'matching-v1', 'OPEN', clock_timestamp() + INTERVAL '1 day', now(), now()) RETURNING id
				""", Long.class, owner.getId());

		for (User outsider : List.of(foreign, admin)) {
			HttpResponse<String> response = get(reportId, tokens.issue(outsider).value());
			assertThat(response.statusCode()).isEqualTo(404);
			JsonNode body = json.readTree(response.body());
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
			assertThat(body.get("code").asString()).isEqualTo("COMMON-004");
		}
	}

	@Test
	void unauthenticatedAndRetiredP0RoutesUseExactErrorContracts() throws Exception {
		HttpResponse<String> unauthenticated = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/lost-reports/1/candidates"))
						.GET().build(), HttpResponse.BodyHandlers.ofString());
		assertError(unauthenticated, 401, "COMMON-002");

		User user = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		String token = tokens.issue(user).value();
		for (String path : List.of(
				"/api/v1/found-items/999/images",
				"/api/v1/found-items/999/nearby-lost-centers")) {
			HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
					URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + token)
					.GET().build(), HttpResponse.BodyHandlers.ofString());
			assertError(response, 404, "COMMON-004");
		}
	}

	private HttpResponse<String> get(long reportId, String token) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
				"http://localhost:" + port + "/api/v1/lost-reports/" + reportId + "/candidates"))
				.header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(body.get("code").asString()).isEqualTo(code);
	}
}
