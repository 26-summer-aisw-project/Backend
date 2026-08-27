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
@Import({PostgresTestContainerConfig.class, FoundItemObjectStorageIntegrationTest.StorageTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiContractIntegrationTest {

	@LocalServerPort int port;
	@Autowired JwtTokenService tokens;
	@Autowired UserRepository users;
	@Autowired JdbcTemplate jdbc;
	private final ObjectMapper json = new ObjectMapper();
	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void everyMatrixRowHasAValidRoleAwareRealHttpSuccessFixture() throws Exception {
		assertThat(ApiContractMatrix.OPERATIONS).hasSize(32);
		ApiContractSuccessFixture fixtures = new ApiContractSuccessFixture(port, tokens, users, jdbc, json);
		ApiContractSuccessFixture.Context context = fixtures.seed();
		for (ApiContractMatrix.Operation row : ApiContractMatrix.OPERATIONS) {
			HttpRequest request = fixtures.request(row, context);
			HttpResponse<String> response = httpClient.send(request,
				HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).as(row.key()).isEqualTo(row.successStatus());
			JsonNode body = json.readTree(response.body());
			assertThat(body.propertyNames()).as(row.key() + " success fields")
				.containsExactlyInAnyOrderElementsOf(row.successFields());
			assertFlagOutputs(row, body);
			fixtures.capture(row, response, context);
			for (String decimalParameter : row.decimalPathParameters()) {
				HttpResponse<String> malformed = send(fixtures.malformedDecimal(row, request, decimalParameter));
				assertError(malformed, 400, "COMMON-001");
				System.out.println("MATRIX_DECIMAL_BOUNDARY key=" + row.key() + " parameter="
					+ decimalParameter + " valid=<DECIMAL_ID> malformed=400");
			}
			System.out.println("MATRIX_SUCCESS key=" + row.key() + " role=" + row.security()
				+ " status=" + response.statusCode() + " fields=" + new java.util.TreeSet<>(row.successFields()));
		}
		for (ApiContractMatrix.Security security : List.of(
			ApiContractMatrix.Security.USER, ApiContractMatrix.Security.ADMIN,
			ApiContractMatrix.Security.CENTER_MANAGER)) {
			HttpResponse<String> denied = httpClient.send(fixtures.wrongRole(security, context),
				HttpResponse.BodyHandlers.ofString());
			assertError(denied, 403, "COMMON-003");
		}
		for (ApiContractMatrix.Operation row : ApiContractMatrix.OPERATIONS) {
			if (row.flags().contains(ApiContractMatrix.Flag.PAGE)) {
				assertError(send(fixtures.invalidPage(row, context)), 400, "COMMON-001");
			}
		}
		assertError(send(fixtures.invalidIdempotency(context)), 400, "COMMON-001");
		assertError(send(fixtures.nonEmptyCloseBody(context)), 400, "COMMON-001");
		assertError(send(fixtures.malformedJson()), 400, "COMMON-001");
		assertError(send(fixtures.missingMultipartImage(context)), 400, "COMMON-001");

		ApiContractMatrix.Operation replayRow = ApiContractMatrix.OPERATIONS.stream()
			.filter(row -> row.flags().contains(ApiContractMatrix.Flag.REPLAY)).findFirst().orElseThrow();
		HttpResponse<String> replay = send(fixtures.replay(context));
		assertThat(replay.statusCode()).isEqualTo(replayRow.successStatus());
		JsonNode replayBody = json.readTree(replay.body());
		assertThat(replayBody.propertyNames()).containsExactlyInAnyOrderElementsOf(replayRow.successFields());
		assertThat(replayBody.path("replayed").asBoolean()).isTrue();
		System.out.println("MATRIX_BOUNDARIES pageRows=4 decimalRows=16 idempotency=400 emptyJson=400"
			+ " malformedJson=400 multipart=400 replayed=true wrongRoles=[USER,ADMIN,CENTER_MANAGER]");
	}

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

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private void assertFlagOutputs(ApiContractMatrix.Operation row, JsonNode body) {
		assertDecimalIds(body);
		if (row.flags().contains(ApiContractMatrix.Flag.SIGNED_URL) && body.has("url")) {
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("url", "expiresAt");
			assertThat(body.toString()).doesNotContain("objectKey", "storagePath", "imageBytes");
		}
		if (row.flags().contains(ApiContractMatrix.Flag.PRIVATE_NON_PERSISTENT)) {
			assertThat(body.toString()).doesNotContain("privateFeatures", "request-only-contract-check");
		}
	}

	private void assertDecimalIds(JsonNode node) {
		if (node.isObject()) {
			node.properties().forEach(entry -> {
				if ((entry.getKey().equals("id") || entry.getKey().endsWith("Id"))
						&& entry.getValue().isTextual() && !entry.getValue().isNull()) {
					assertThat(entry.getValue().asString()).matches("[1-9][0-9]*");
				}
				assertDecimalIds(entry.getValue());
			});
		} else if (node.isArray()) {
			node.forEach(this::assertDecimalIds);
		}
	}

	private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(body.get("code").asString()).isEqualTo(code);
	}
}
