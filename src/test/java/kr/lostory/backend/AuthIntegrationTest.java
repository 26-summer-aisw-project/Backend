package kr.lostory.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthIntegrationTest {

	private static final Set<String> USER_KEYS = Set.of("id", "email", "roles");
	private static final Set<String> ERROR_KEYS = Set.of("code", "message", "fieldErrors", "timestamp");
	private static final String PASSWORD = "Correct-Horse-42";

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtTokenService tokenService;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void openApiDescribesJwtBearerAndEndpointSecurity() throws Exception {
		// Given / When
		HttpResponse<String> response = get("/v3/api-docs", null);
		JsonNode api = json(response);

		// Then
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode bearerAuth = api.get("components").get("securitySchemes").get("bearerAuth");
		assertThat(bearerAuth.get("type").asString()).isEqualTo("http");
		assertThat(bearerAuth.get("scheme").asString()).isEqualTo("bearer");
		assertThat(bearerAuth.get("bearerFormat").asString()).isEqualTo("JWT");
		assertThat(api.get("paths").get("/api/v1/auth/signup").get("post").get("security")).isNull();
		assertThat(api.get("paths").get("/api/v1/auth/login").get("post").get("security")).isNull();
		assertThat(api.get("paths").get("/api/v1/users/me").get("get").get("security").get(0).has("bearerAuth"))
			.isTrue();
	}

	@Test
	void publicEndpointsAndMalformedRequestRemainStateless() throws Exception {
		// Given / When
		HttpResponse<String> health = get("/actuator/health", null);
		HttpResponse<String> docs = get("/v3/api-docs", null);
		HttpResponse<String> malformed = rawPost("/api/v1/auth/signup", "{");

		// Then
		assertThat(health.statusCode()).isEqualTo(200);
		assertThat(fieldNames(json(health))).containsExactlyInAnyOrder("status", "groups");
		assertThat(docs.statusCode()).isEqualTo(200);
		assertError(malformed, 400, "COMMON-001");
		assertNoSessionCookie(health);
		assertNoSessionCookie(docs);
		assertNoSessionCookie(malformed);
	}

	@Test
	void signupLoginAndMeUseExactContracts() throws Exception {
		// Given
		String email = uniqueEmail();

		// When
		HttpResponse<String> signup = post("/api/v1/auth/signup", signupCredentials("  " + email.toUpperCase() + "  ", PASSWORD));
		HttpResponse<String> login = post("/api/v1/auth/login", credentials(" " + email.toUpperCase() + " ", PASSWORD));
		JsonNode loginJson = json(login);
		assertThat(signup.statusCode()).describedAs(signup.body()).isEqualTo(201);
		assertUser(json(signup), email);
		assertThat(login.statusCode()).describedAs(login.body()).isEqualTo(200);
		HttpResponse<String> me = get("/api/v1/users/me", loginJson.get("accessToken").asString());

		// Then
		assertThat(fieldNames(loginJson)).containsExactlyInAnyOrder("accessToken", "tokenType", "expiresAt", "user");
		assertThat(loginJson.get("accessToken").asString()).isNotBlank();
		assertThat(loginJson.get("tokenType").asString()).isEqualTo("Bearer");
		assertThat(loginJson.get("expiresAt").asString()).isNotBlank();
		assertUser(loginJson.get("user"), email);
		assertThat(me.statusCode()).isEqualTo(200);
		assertUser(json(me), email);
		assertNoSessionCookie(signup);
		assertNoSessionCookie(login);
		assertNoSessionCookie(me);
	}

	@Test
	void signupPersistsDisplayNameAndRequiresIt() throws Exception {
		// Given
		String email = uniqueEmail();

		// When
		HttpResponse<String> signup = post("/api/v1/auth/signup", signupCredentials(email, PASSWORD, "  새 사용자  "));
		HttpResponse<String> missingName = post(
				"/api/v1/auth/signup",
				objectMapper.writeValueAsString(Map.of("email", uniqueEmail(), "password", PASSWORD))
		);
		HttpResponse<String> blankName = post("/api/v1/auth/signup", signupCredentials(uniqueEmail(), PASSWORD, " "));

		// Then
		assertThat(signup.statusCode()).isEqualTo(201);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT display_name FROM users WHERE email = ?",
				String.class,
				email
		)).isEqualTo("새 사용자");
		assertError(missingName, 400, "COMMON-001");
		assertError(blankName, 400, "COMMON-001");
	}

	@Test
	void concurrentDuplicateAndInvalidCredentialsFailSafely() throws Exception {
		// Given
		String email = uniqueEmail();
		String bodyOne = signupCredentials(" " + email.toUpperCase() + " ", PASSWORD);
		String bodyTwo = signupCredentials(email, PASSWORD);

		// When
		HttpResponse<String> first;
		HttpResponse<String> second;
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			CompletableFuture<HttpResponse<String>> firstFuture = CompletableFuture.supplyAsync(() -> uncheckedPost(bodyOne), executor);
			CompletableFuture<HttpResponse<String>> secondFuture = CompletableFuture.supplyAsync(() -> uncheckedPost(bodyTwo), executor);
			first = firstFuture.join();
			second = secondFuture.join();
		}

		// Then
		assertThat(new int[]{first.statusCode(), second.statusCode()}).containsExactlyInAnyOrder(201, 409);
		assertThat(json(first.statusCode() == 409 ? first : second).get("code").asString()).isEqualTo("AUTH-001");
		assertThat(jdbcTemplate.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isEqualTo(1);

		HttpResponse<String> unknown = post("/api/v1/auth/login", credentials(uniqueEmail(), PASSWORD));
		HttpResponse<String> wrong = post("/api/v1/auth/login", credentials(email, "Wrong-Password-42"));
		jdbcTemplate.update("update users set status = 'BLOCKED' where email = ?", email);
		HttpResponse<String> inactive = post("/api/v1/auth/login", credentials(email, PASSWORD));
		assertThat(stableError(unknown)).isEqualTo(stableError(wrong)).isEqualTo(stableError(inactive));
		assertThat(stableError(unknown)).containsEntry("status", 401).containsEntry("code", "AUTH-002");

		String activeEmail = uniqueEmail();
		post("/api/v1/auth/signup", signupCredentials(activeEmail, PASSWORD));
		String token = json(post("/api/v1/auth/login", credentials(activeEmail, PASSWORD))).get("accessToken").asString();
		jdbcTemplate.update("update users set status = 'BLOCKED' where email = ?", activeEmail);
		HttpResponse<String> staleToken = get("/api/v1/users/me", token);
		assertError(staleToken, 401, "AUTH-003");
		assertError(get("/api/v1/users/me", null), 401, "COMMON-002");

		assertError(rawPost("/api/v1/auth/signup", "{"), 400, "COMMON-001");
		assertError(post("/api/v1/auth/signup", signupCredentials("not-an-email", PASSWORD)), 400, "COMMON-001");
		assertError(post("/api/v1/auth/signup", signupCredentials("a".repeat(314) + "@x.test", PASSWORD)), 400, "COMMON-001");
		assertError(post("/api/v1/auth/signup", signupCredentials(uniqueEmail(), "1234567")), 400, "COMMON-001");
		assertThat(post("/api/v1/auth/signup", signupCredentials(uniqueEmail(), "12345678")).statusCode()).isEqualTo(201);
		assertThat(post("/api/v1/auth/signup", signupCredentials(uniqueEmail(), "가".repeat(24))).statusCode()).isEqualTo(201);
		assertError(post("/api/v1/auth/signup", signupCredentials(uniqueEmail(), "가".repeat(25))), 400, "COMMON-001");
		assertThat(post("/api/v1/auth/signup", signupCredentials(uniqueEmail(), "a".repeat(72))).statusCode()).isEqualTo(201);
		assertError(post("/api/v1/auth/signup", signupCredentials(uniqueEmail(), "a".repeat(73))), 400, "COMMON-001");
	}

	@Test
	void missingUserSubjectReturnsAuth003() throws Exception {
		// Given
		long missingUserId = Long.MAX_VALUE;
		User missingUser = mock(User.class);
		when(missingUser.getId()).thenReturn(missingUserId);
		when(missingUser.getRoles()).thenReturn(Set.of(UserRole.USER));
		assertThat(jdbcTemplate.queryForObject("select count(*) from users where id = ?", Integer.class, missingUserId))
			.isZero();
		String token = tokenService.issue(missingUser).value();

		// When
		HttpResponse<String> response = get("/api/v1/users/me", token);

		// Then
		assertError(response, 401, "AUTH-003");
	}

	private HttpResponse<String> uncheckedPost(String body) {
		try {
			return post("/api/v1/auth/signup", body);
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private String uniqueEmail() {
		return "auth-" + UUID.randomUUID() + "@example.test";
	}

	private String credentials(String email, String password) throws Exception {
		return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
	}

	private String signupCredentials(String email, String password) throws Exception {
		return signupCredentials(email, password, "테스트 사용자");
	}

	private String signupCredentials(String email, String password, String displayName) throws Exception {
		return objectMapper.writeValueAsString(Map.of(
				"email", email,
				"password", password,
				"displayName", displayName
		));
	}

	private HttpResponse<String> post(String path, String body) throws Exception {
		return rawPost(path, body);
	}

	private HttpResponse<String> rawPost(String path, String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body))
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode json(HttpResponse<String> response) throws Exception {
		return objectMapper.readTree(response.body());
	}

	private Set<String> fieldNames(JsonNode node) {
		return node.propertyNames().stream().collect(java.util.stream.Collectors.toSet());
	}

	private void assertUser(JsonNode user, String email) {
		assertThat(fieldNames(user)).isEqualTo(USER_KEYS);
		assertThat(user.get("id").asLong()).isPositive();
		assertThat(user.get("email").asString()).isEqualTo(email);
		assertThat(user.get("roles").values().stream().map(JsonNode::asString).toList()).containsExactly("USER");
	}

	private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		JsonNode error = json(response);
		assertThat(fieldNames(error)).isEqualTo(ERROR_KEYS);
		assertThat(error.get("code").asString()).isEqualTo(code);
	}

	private void assertNoSessionCookie(HttpResponse<String> response) {
		assertThat(response.headers().allValues("set-cookie"))
			.noneMatch(value -> value.contains("JSESSIONID"));
	}

	private Map<String, Object> stableError(HttpResponse<String> response) throws Exception {
		JsonNode error = json(response);
		return Map.of(
			"status", response.statusCode(),
			"code", error.get("code").asString(),
			"message", error.get("message").asString(),
			"fieldErrors", error.get("fieldErrors").toString());
	}
}
