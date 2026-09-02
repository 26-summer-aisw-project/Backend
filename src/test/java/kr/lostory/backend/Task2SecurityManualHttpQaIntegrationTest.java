package kr.lostory.backend;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.config.JwtProperties;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Task2SecurityManualHttpQaIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private JwtTokenService tokenService;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void curlMatrixProvesActiveStaleMalformedManagerAndActivationSecurity() throws Exception {
		// Given
		User active = saveUser("active", UserRole.USER);
		User blocked = saveUser("blocked", UserRole.USER);
		User deleted = saveUser("deleted", UserRole.USER);
		User manager = saveUser("manager", UserRole.CENTER_MANAGER);
		String activeToken = tokenService.issue(active).value();
		String blockedToken = tokenService.issue(blocked).value();
		String deletedToken = tokenService.issue(deleted).value();
		String managerToken = tokenService.issue(manager).value();
		String nonnumericToken = encodeNonnumericSubject();
		jdbcTemplate.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", blocked.getId());
		jdbcTemplate.update("UPDATE users SET status = 'DELETED' WHERE id = ?", deleted.getId());

		// When
		CurlResult activeProfile = curl("GET", "/api/v1/users/me", activeToken);
		CurlResult blockedProfile = curl("GET", "/api/v1/users/me", blockedToken);
		CurlResult deletedProfile = curl("GET", "/api/v1/users/me", deletedToken);
		CurlResult malformedProfile = curl("GET", "/api/v1/users/me", nonnumericToken);
		CurlResult managerProfile = curl("GET", "/api/v1/users/me", managerToken);
		CurlResult managerProfilePost = curl("POST", "/api/v1/users/me", managerToken);
		CurlResult managerItem = curl("GET", "/api/v1/found-items/1", managerToken);
		CurlResult managerImage = curl("GET", "/api/v1/found-items/1/image", managerToken);
		CurlResult activationPost = curl("POST", "/api/v1/partner-manager-activations/test-token", null,
			"{\"password\":\"safe-password-123\"}");
		CurlResult activationGet = curl("GET", "/api/v1/partner-manager-activations/test-token", null);
		CurlResult activationExtra = curl("POST", "/api/v1/partner-manager-activations/test-token/extra", null);

		// Then
		assertProfile(activeProfile, "USER");
		assertError(blockedProfile, 401, "AUTH-003", "The access token is invalid.");
		assertError(deletedProfile, 401, "AUTH-003", "The access token is invalid.");
		assertError(malformedProfile, 401, "AUTH-003", "The access token is invalid.");
		assertProfile(managerProfile, "CENTER_MANAGER");
		assertError(managerProfilePost, 403, "COMMON-003", "You do not have permission to access this resource.");
		assertError(managerItem, 403, "COMMON-003", "You do not have permission to access this resource.");
		assertError(managerImage, 404, "COMMON-004", "The requested resource could not be found.");
		assertError(activationPost, 404, "COMMON-004", "The requested resource could not be found.");
		assertError(activationGet, 401, "COMMON-002", "Authentication is required.");
		assertError(activationExtra, 401, "COMMON-002", "Authentication is required.");
	}

	private User saveUser(String prefix, UserRole role) {
		return userRepository.saveAndFlush(new User(
			prefix + "-" + UUID.randomUUID() + "@example.test", "test-password-hash", prefix, role));
	}

	private String encodeNonnumericSubject() {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(jwtProperties.issuer())
			.subject("not-a-number")
			.issuedAt(now.minusSeconds(1))
			.expiresAt(now.plusSeconds(300))
			.claim("roles", List.of("USER"))
			.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	private CurlResult curl(String method, String path, String token) throws Exception {
		return curl(method, path, token, null);
	}

	private CurlResult curl(String method, String path, String token, String requestBody) throws Exception {
		List<String> command = new java.util.ArrayList<>(List.of(
			"curl", "-sS", "-i", "--max-time", "15", "-X", method));
		if (token != null) {
			command.addAll(List.of("-H", "Authorization: Bearer " + token));
		}
		if (requestBody != null) {
			command.addAll(List.of("-H", "Content-Type: application/json", "--data", requestBody));
		}
		command.add("http://127.0.0.1:" + port + path);
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String response;
		try {
			response = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			if (!process.waitFor(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("curl exceeded cleanup deadline");
			}
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				process.waitFor(5, TimeUnit.SECONDS);
			}
		}
		assertThat(process.exitValue()).isZero();
		int bodyStart = response.lastIndexOf("\r\n\r\n");
		assertThat(bodyStart).isGreaterThan(0);
		String statusLine = response.substring(0, response.indexOf("\r\n"));
		int status = Integer.parseInt(statusLine.split(" ")[1]);
		JsonNode body = objectMapper.readTree(response.substring(bodyStart + 4));
		String redactedHeader = token == null ? "" : " -H 'Authorization: Bearer <REDACTED>'";
		String methodOption = "GET".equals(method) ? "" : " -X " + method;
		String redactedBody = requestBody == null ? ""
			: " -H 'Content-Type: application/json' --data '{\"password\":\"<REDACTED>\"}'";
		System.out.printf("CURL: curl -i --max-time 15%s%s%s http://127.0.0.1:%d%s%n",
			redactedHeader, methodOption, redactedBody, port, path);
		System.out.printf("OBSERVED: status=%d body.fields=%s code=%s%n",
			status, body.propertyNames(), body.has("code") ? body.get("code").asString() : "<none>");
		return new CurlResult(status, body);
	}

	private void assertProfile(CurlResult result, String role) {
		assertThat(result.status()).isEqualTo(200);
		assertThat(result.body().get("roles").get(0).asString()).isEqualTo(role);
	}

	private void assertError(CurlResult result, int status, String code, String message) {
		assertThat(result.status()).isEqualTo(status);
		assertThat(result.body().propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(result.body().get("code").asString()).isEqualTo(code);
		assertThat(result.body().get("message").asString()).isEqualTo(message);
		assertThat(result.body().toString()).doesNotContain("BLOCKED", "DELETED", "not-a-number");
	}

	private record CurlResult(int status, JsonNode body) {
	}
}
