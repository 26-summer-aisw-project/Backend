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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
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

	@Test
	void validJwtHasAllowlistedClaimsAndMappedRoles() throws Exception {
		// Given
		User user = mock(User.class);
		when(user.getId()).thenReturn(42L);
		when(user.getRoles()).thenReturn(Set.of(UserRole.USER, UserRole.ADMIN));

		// When
		JwtTokenService.IssuedToken issued = tokenService.issue(user);
		Jwt decoded = jwtDecoder.decode(issued.value());
		HttpResponse<String> response = request(issued.value());

		// Then
		assertThat(decoded.getHeaders()).containsEntry("alg", "HS256");
		assertThat(decoded.getClaims().keySet()).containsExactlyInAnyOrder("iss", "sub", "iat", "exp", "roles");
		assertThat(decoded.getSubject()).isEqualTo("42");
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
		HttpRequest.Builder request = HttpRequest.newBuilder(
			URI.create("http://localhost:" + port + "/api/v1/protected-unmatched"));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private String encode(String issuer, Instant issuedAt, Instant expiresAt, Object roles, boolean includeRoles) {
		return encode(jwtEncoder, issuer, issuedAt, expiresAt, roles, includeRoles);
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

	private void assertJsonError(String body, String code) throws Exception {
		var json = objectMapper.readTree(body);
		assertThat(json.propertyNames()).containsExactlyInAnyOrder("code", "message", "fieldErrors", "timestamp");
		assertThat(json.get("code").asString()).isEqualTo(code);
	}

	private void assertNoSession(HttpResponse<?> response) {
		assertThat(response.headers().allValues("set-cookie")).noneMatch(value -> value.contains("JSESSIONID"));
	}
}
