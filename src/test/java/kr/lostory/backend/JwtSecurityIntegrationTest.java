package kr.lostory.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
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
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JwtSecurityIntegrationTest {

	@LocalServerPort
	private int port;

	@Autowired
	private JwtTokenService tokenService;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private JwtDecoder jwtDecoder;

	@Autowired
	private JwtAuthenticationConverter jwtAuthenticationConverter;

	@Autowired
	private AccessDeniedHandler accessDeniedHandler;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void activeUserAndAdminJwtRetainCurrentHttpBehavior() throws Exception {
		// Given
		User user = userRepository.saveAndFlush(new User(uniqueEmail("user"), "test-password-hash", "Active User"));
		User admin = userRepository.saveAndFlush(
			new User(uniqueEmail("admin"), "test-password-hash", "Active Admin", UserRole.ADMIN));

		// When
		HttpResponse<String> userProfile = get("/api/v1/users/me", tokenService.issue(user).value());
		HttpResponse<String> adminProfile = get("/api/v1/users/me", tokenService.issue(admin).value());
		HttpResponse<String> userMissingItem = get("/api/v1/found-items/9223372036854775807",
			tokenService.issue(user).value());
		HttpResponse<String> adminMissingItem = get("/api/v1/found-items/9223372036854775807",
			tokenService.issue(admin).value());

		// Then
		assertThat(userProfile.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(userProfile.body()).get("roles").get(0).asString()).isEqualTo("USER");
		assertThat(adminProfile.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(adminProfile.body()).get("roles").get(0).asString()).isEqualTo("ADMIN");
		assertThat(userMissingItem.statusCode()).isEqualTo(404);
		assertJsonError(userMissingItem.body(), "COMMON-004");
		assertThat(adminMissingItem.statusCode()).isEqualTo(404);
		assertJsonError(adminMissingItem.body(), "COMMON-004");
	}

	@Test
	void validJwtHasAllowlistedClaimsAndMappedRoles() throws Exception {
		// Given
		User activeUser = userRepository.saveAndFlush(
			new User(uniqueEmail("claims"), "test-password-hash", "Claims User"));
		User user = mock(User.class);
		when(user.getId()).thenReturn(activeUser.getId());
		when(user.getRoles()).thenReturn(Set.of(UserRole.USER, UserRole.ADMIN));

		// When
		JwtTokenService.IssuedToken issued = tokenService.issue(user);
		Jwt decoded = jwtDecoder.decode(issued.value());
		HttpResponse<String> response = request(issued.value());

		// Then
		assertThat(decoded.getHeaders()).containsEntry("alg", "HS256");
		assertThat(decoded.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "roles");
		assertThat(decoded.getSubject()).isEqualTo(activeUser.getId().toString());
		assertThat(decoded.getClaimAsStringList("roles")).containsExactlyInAnyOrder("USER", "ADMIN");
		assertThat(jwtAuthenticationConverter.convert(decoded).getAuthorities().stream()
			.filter(authority -> authority.getAuthority().startsWith("ROLE_")).toList()).containsExactlyInAnyOrder(
			new SimpleGrantedAuthority("ROLE_USER"),
			new SimpleGrantedAuthority("ROLE_ADMIN"));
		assertThat(response.statusCode()).isEqualTo(404);
		assertNoSession(response);
	}

	@Test
	void invalidBearerVariantsReturnGeneric401WithoutSession() throws Exception {
		// Given
		Instant now = Instant.now();
		String valid = encode(jwtProperties.issuer(), now.minusSeconds(1), now.plusSeconds(300), List.of("USER"), true);
		String altered = alterSignature(valid);
		byte[] wrongSecret = jwtProperties.secret();
		wrongSecret[0] ^= 1;
		JwtEncoder wrongKeyEncoder = new NimbusJwtEncoder(
			new ImmutableSecret<SecurityContext>(new SecretKeySpec(wrongSecret, "HmacSHA256")));
		Map<String, String> invalidTokens = Map.of(
			"malformed", "not-a-jwt",
			"altered", altered,
			"wrong-key", encode(wrongKeyEncoder, jwtProperties.issuer(), now.minusSeconds(1), now.plusSeconds(300), List.of("USER"), true),
			"expired", encode(jwtProperties.issuer(), now.minusSeconds(600), now.minusSeconds(300), List.of("USER"), true),
			"wrong-issuer", encode("https://wrong-issuer.test.invalid", now.minusSeconds(1), now.plusSeconds(300), List.of("USER"), true),
			"missing-role", encode(jwtProperties.issuer(), now.minusSeconds(1), now.plusSeconds(300), null, false),
			"scalar-role", encode(jwtProperties.issuer(), now.minusSeconds(1), now.plusSeconds(300), "USER", true),
			"empty-role", encode(jwtProperties.issuer(), now.minusSeconds(1), now.plusSeconds(300), List.of(), true),
			"unknown-role", encode(jwtProperties.issuer(), now.minusSeconds(1), now.plusSeconds(300), List.of("OWNER"), true));

		// When / Then
		HttpResponse<String> missing = request(null);
		assertUnauthorized(missing, "COMMON-002");
		for (Map.Entry<String, String> variant : invalidTokens.entrySet()) {
			HttpResponse<String> response = request(variant.getValue());
			assertThat(response.statusCode()).as(variant.getKey()).isEqualTo(401);
			assertJsonError(response.body(), "AUTH-003");
			assertThat(response.body()).as(variant.getKey())
				.doesNotContain("Jwt", "JWT", "signature", "issuer", "roles");
			assertNoSession(response);
		}
	}

	@Test
	void inactiveJwtSubjectsAreRejectedBeforeProtectedServices() throws Exception {
		// Given
		User blocked = userRepository.saveAndFlush(
			new User(uniqueEmail("blocked"), "test-password-hash", "Blocked User"));
		User deleted = userRepository.saveAndFlush(
			new User(uniqueEmail("deleted"), "test-password-hash", "Deleted User"));
		String blockedToken = tokenService.issue(blocked).value();
		String deletedToken = tokenService.issue(deleted).value();
		jdbcTemplate.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", blocked.getId());
		jdbcTemplate.update("UPDATE users SET status = 'DELETED' WHERE id = ?", deleted.getId());

		// When
		Map<String, HttpResponse<String>> responses = Map.of(
			"blocked-profile", get("/api/v1/users/me", blockedToken),
			"blocked-item", get("/api/v1/found-items/9223372036854775807", blockedToken),
			"deleted-profile", get("/api/v1/users/me", deletedToken),
			"deleted-item", get("/api/v1/found-items/9223372036854775807", deletedToken));

		// Then
		for (Map.Entry<String, HttpResponse<String>> response : responses.entrySet()) {
			assertThat(response.getValue().statusCode()).as(response.getKey()).isEqualTo(401);
			assertJsonError(response.getValue().body(), "AUTH-003");
			assertThat(response.getValue().body()).doesNotContain("BLOCKED", "DELETED", "status", "User");
		}
	}

	@Test
	void missingNonnumericAndNonexistentJwtSubjectsReturnSafeInvalidToken() throws Exception {
		// Given
		Instant now = Instant.now();
		Map<String, String> tokens = Map.of(
			"missing", encodeSubject(null, false, now),
			"nonnumeric", encodeSubject("not-a-number", true, now),
			"nonexistent", encodeSubject("9223372036854775807", true, now));

		// When / Then
		for (Map.Entry<String, String> token : tokens.entrySet()) {
			HttpResponse<String> profile = get("/api/v1/users/me", token.getValue());
			HttpResponse<String> protectedItem = get("/api/v1/found-items/9223372036854775807", token.getValue());
			for (HttpResponse<String> response : List.of(profile, protectedItem)) {
				assertThat(response.statusCode()).as(token.getKey()).isEqualTo(401);
				assertJsonError(response.body(), "AUTH-003");
				assertThat(response.body()).doesNotContain("subject", "NumberFormatException", "not-a-number");
			}
		}
	}

	@Test
	void centerManagerRouteMatrixAndExactActivationPostAreScoped() throws Exception {
		// Given
		long managerId = jdbcTemplate.queryForObject("""
			INSERT INTO users (email, password_hash, display_name, status, role, created_at, updated_at)
			VALUES (?, 'test-password-hash', 'Center Manager', 'ACTIVE', 'CENTER_MANAGER', NOW(), NOW())
			RETURNING id
			""", Long.class, uniqueEmail("manager"));
		User user = userRepository.saveAndFlush(new User(uniqueEmail("route-user"), "test-password-hash", "Route User"));
		User admin = userRepository.saveAndFlush(
			new User(uniqueEmail("route-admin"), "test-password-hash", "Route Admin", UserRole.ADMIN));
		Instant now = Instant.now();
		String managerToken = encodeSubjectAndRoles(Long.toString(managerId), List.of("CENTER_MANAGER"), now);
		String userToken = tokenService.issue(user).value();
		String adminToken = tokenService.issue(admin).value();

		// When
		HttpResponse<String> managerProfile = get("/api/v1/users/me", managerToken);
		HttpResponse<String> managerProfilePost = exchange(HttpMethod.POST, "/api/v1/users/me", managerToken);
		Map<String, HttpResponse<String>> managerDenied = Map.of(
			"found-item", get("/api/v1/found-items/1", managerToken),
			"found-item-image", get("/api/v1/found-items/1/image", managerToken),
			"lost-report", get("/api/v1/lost-reports/1", managerToken),
			"lost-center", get("/api/v1/lost-centers", managerToken));
		HttpResponse<String> managerDashboard = get("/api/v1/dashboard/unmapped", managerToken);
		HttpResponse<String> managerAdmin = get("/api/v1/admin/unmapped", managerToken);
		HttpResponse<String> userDashboard = get("/api/v1/dashboard/unmapped", userToken);
		HttpResponse<String> adminDashboard = get("/api/v1/dashboard/unmapped", adminToken);
		HttpResponse<String> adminAdmin = get("/api/v1/admin/unmapped", adminToken);
		HttpResponse<String> activationPost = exchange(HttpMethod.POST,
			"/api/v1/partner-manager-activations/test-token", null);
		HttpResponse<String> activationGet = get("/api/v1/partner-manager-activations/test-token", null);
		HttpResponse<String> activationExtra = exchange(HttpMethod.POST,
			"/api/v1/partner-manager-activations/test-token/extra", null);

		// Then
		assertThat(managerProfile.statusCode()).isEqualTo(200);
		assertThat(objectMapper.readTree(managerProfile.body()).get("roles").get(0).asString())
			.isEqualTo("CENTER_MANAGER");
		assertForbidden(managerProfilePost);
		for (Map.Entry<String, HttpResponse<String>> response : managerDenied.entrySet()) {
			assertThat(response.getValue().statusCode()).as(response.getKey()).isEqualTo(403);
			assertJsonError(response.getValue().body(), "COMMON-003");
		}
		assertThat(managerDashboard.statusCode()).isEqualTo(404);
		assertJsonError(managerDashboard.body(), "COMMON-004");
		assertForbidden(managerAdmin);
		assertForbidden(userDashboard);
		assertForbidden(adminDashboard);
		assertThat(adminAdmin.statusCode()).isEqualTo(404);
		assertJsonError(adminAdmin.body(), "COMMON-004");
		assertThat(activationPost.statusCode()).isEqualTo(404);
		assertJsonError(activationPost.body(), "COMMON-004");
		assertUnauthorized(activationGet, "COMMON-002");
		assertUnauthorized(activationExtra, "COMMON-002");
	}

	@Test
	void accessDeniedHandlerWritesCommon003Json() throws Exception {
		// Given
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();

		// When
		accessDeniedHandler.handle(request, response, null);

		// Then
		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentType()).startsWith("application/json");
		assertJsonError(response.getContentAsString(), "COMMON-003");
	}

	private HttpResponse<String> request(String token) throws Exception {
		return get("/api/v1/protected-unmatched", token);
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		return exchange(HttpMethod.GET, path, token);
	}

	private HttpResponse<String> exchange(HttpMethod method, String path, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		request.method(method.name(), HttpRequest.BodyPublishers.noBody());
		return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String uniqueEmail(String prefix) {
		return prefix + "-" + java.util.UUID.randomUUID() + "@example.test";
	}

	private String encode(String issuer, Instant issuedAt, Instant expiresAt, Object roles, boolean includeRoles) {
		return encode(jwtEncoder, issuer, issuedAt, expiresAt, roles, includeRoles);
	}

	private String encodeSubject(String subject, boolean includeSubject, Instant now) {
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer(jwtProperties.issuer())
			.issuedAt(now.minusSeconds(1))
			.expiresAt(now.plusSeconds(300))
			.claim("roles", List.of("USER"));
		if (includeSubject) {
			claims.subject(subject);
		}
		return jwtEncoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
	}

	private String encodeSubjectAndRoles(String subject, List<String> roles, Instant now) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer(jwtProperties.issuer())
			.subject(subject)
			.issuedAt(now.minusSeconds(1))
			.expiresAt(now.plusSeconds(300))
			.claim("roles", roles)
			.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
	}

	private String encode(JwtEncoder encoder, String issuer, Instant issuedAt, Instant expiresAt, Object roles,
		boolean includeRoles) {
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer(issuer)
			.subject("42")
			.issuedAt(issuedAt)
			.expiresAt(expiresAt);
		if (includeRoles) {
			claims.claim("roles", roles);
		}
		return encoder.encode(JwtEncoderParameters.from(
			JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
	}

	private String alterSignature(String token) {
		int signatureStart = token.lastIndexOf('.') + 1;
		char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
		return token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
	}

	private void assertUnauthorized(HttpResponse<String> response, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(401);
		assertJsonError(response.body(), code);
		assertNoSession(response);
	}

	private void assertForbidden(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).isEqualTo(403);
		assertJsonError(response.body(), "COMMON-003");
	}

	private void assertJsonError(String body, String code) throws Exception {
		var json = objectMapper.readTree(body);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(json.get("code").asString()).isEqualTo(code);
	}

	private void assertNoSession(HttpResponse<?> response) {
		assertThat(response.headers().allValues("set-cookie")).noneMatch(value -> value.contains("JSESSIONID"));
	}
}
