package kr.lostory.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class UserDeletedStatusIntegrationTest {

	private static final String PASSWORD = "Correct-Horse-42";

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JwtTokenService tokenService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private UserRepository userRepository;

	@Test
	void deletedUserCannotLoginOrReadTheirProfile() throws Exception {
		// Given
		String email = "deleted-" + UUID.randomUUID() + "@example.test";
		User user = userRepository.saveAndFlush(new User(email, passwordEncoder.encode(PASSWORD), "삭제 사용자"));
		String token = tokenService.issue(user).value();
		jdbcTemplate.update("UPDATE users SET status = 'DELETED' WHERE id = ?", user.getId());

		// When
		HttpResponse<String> login = post("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD));
		HttpResponse<String> profile = get("/api/v1/users/me", token);

		// Then
		assertError(login, 401, "AUTH-002");
		assertError(profile, 401, "AUTH-003");
	}

	private HttpResponse<String> post(String path, Map<String, String> payload) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("Authorization", "Bearer " + token)
				.GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
		JsonNode error = objectMapper.readTree(response.body());
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(error.get("code").asString()).isEqualTo(code);
		assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message");
	}
}
