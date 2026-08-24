package kr.lostory.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;

import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.config.FoundItemProperties;
import kr.lostory.backend.config.JwtProperties;
import kr.lostory.backend.config.LostCenterProperties;
import kr.lostory.backend.config.LostReportProperties;
import kr.lostory.backend.config.MatchingProperties;
import kr.lostory.backend.config.ObjectStorageProperties;
import kr.lostory.backend.config.VisionProperties;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"found-item.draft-ttl=PT23H",
		"found-item.terminal-media-retention=P29D",
		"found-item.ttl=P13D",
		"object-storage.enabled=false",
		"object-storage.endpoint=https://storage.test.invalid",
		"object-storage.region=asia-northeast3",
		"object-storage.bucket=p0-test-bucket",
		"object-storage.path-style=true",
		"object-storage.timeout=PT3S",
		"object-storage.orphan-grace=PT2H",
		"object-storage.orphan-sweep-interval=PT11M",
		"object-storage.orphan-sweep-initial-delay=PT12M",
		"vision.enabled=false",
		"vision.provider=google-cloud-vision",
		"vision.processing-region=asia-northeast3",
		"vision.data-retention=PT0S",
		"vision.cost-limit-usd=9.50",
		"vision.daily-job-limit=99",
		"vision.timeout=PT4S",
		"matching.radius-min=501",
		"matching.radius-base=1001",
		"matching.radius-max=2999",
		"matching.radius-coefficient=0.11",
		"matching.radius-policy-version=p0-test-v2",
		"matching.time-window=PT23H",
		"lost-report.ttl=P13D",
		"center.nearby-radius=999",
		"center.nearby-limit=9"
	}
)
class P0ConfigurationAndErrorIntegrationTest {

	private static final Set<String> ERROR_KEYS = Set.of("code", "message");

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JwtTokenService tokenService;

	@Autowired
	private JwtProperties jwtProperties;

	@Autowired
	private FoundItemProperties foundItemProperties;

	@Autowired
	private ObjectStorageProperties objectStorageProperties;

	@Autowired
	private VisionProperties visionProperties;

	@Autowired
	private MatchingProperties matchingProperties;

	@Autowired
	private LostReportProperties lostReportProperties;

	@Autowired
	private LostCenterProperties lostCenterProperties;

	@Autowired
	private Clock clock;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void malformedInputReturnsExactP0ErrorContract(CapturedOutput output) throws Exception {
		// Given
		HttpRequest request = request("/api/v1/auth/signup")
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{"))
			.build();

		// When
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		// Then
		assertError(response, 400, "COMMON-001");
		assertNoSecret(response.body(), output);
		System.out.println("P0_HTTP_OBSERVABLE malformed status=400 keys=[code,message] secret_present=false");
	}

	@Test
	void invalidJwtReturnsExactP0ErrorContract(CapturedOutput output) throws Exception {
		// Given
		HttpRequest request = request("/api/v1/protected-unmatched")
			.header("Authorization", "Bearer not-a-jwt")
			.GET()
			.build();

		// When
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		// Then
		assertError(response, 401, "AUTH-003");
		assertNoSecret(response.body(), output);
		System.out.println("P0_HTTP_OBSERVABLE invalid-jwt status=401 keys=[code,message] secret_present=false");
	}

	@Test
	void authenticatedMissingResourceReturnsExactP0ErrorContract(CapturedOutput output) throws Exception {
		// Given
		User user = mock(User.class);
		when(user.getId()).thenReturn(42L);
		when(user.getRoles()).thenReturn(Set.of(UserRole.USER));
		String token = tokenService.issue(user).value();
		HttpRequest request = request("/api/v1/found-items/9223372036854775807")
			.header("Authorization", "Bearer " + token)
			.GET()
			.build();

		// When
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

		// Then
		assertError(response, 404, "COMMON-004");
		assertNoSecret(response.body(), output);
		System.out.println("P0_HTTP_OBSERVABLE missing-resource status=404 keys=[code,message] secret_present=false");
	}

	@Test
	void deploymentPropertiesAndUtcClockAreInjected() {
		// Given
		Instant before = Instant.now();

		// When
		Instant configuredNow = clock.instant();

		// Then
		assertThat(configuredNow).isBetween(before, Instant.now());
		assertThat(clock.getZone().getId()).isEqualTo("Z");
		assertThat(foundItemProperties.draftTtl()).isEqualTo(Duration.ofHours(23));
		assertThat(foundItemProperties.terminalMediaRetention()).isEqualTo(Duration.ofDays(29));
		assertThat(foundItemProperties.ttl()).isEqualTo(Duration.ofDays(13));
		assertThat(objectStorageProperties.enabled()).isFalse();
		assertThat(objectStorageProperties.endpoint()).isEqualTo(URI.create("https://storage.test.invalid"));
		assertThat(objectStorageProperties.region()).isEqualTo("asia-northeast3");
		assertThat(objectStorageProperties.bucket()).isEqualTo("p0-test-bucket");
		assertThat(objectStorageProperties.pathStyle()).isTrue();
		assertThat(objectStorageProperties.timeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(objectStorageProperties.orphanGrace()).isEqualTo(Duration.ofHours(2));
		assertThat(objectStorageProperties.orphanSweepInterval()).isEqualTo(Duration.ofMinutes(11));
		assertThat(objectStorageProperties.orphanSweepInitialDelay()).isEqualTo(Duration.ofMinutes(12));
		assertThat(visionProperties.enabled()).isFalse();
		assertThat(visionProperties.provider()).isEqualTo("google-cloud-vision");
		assertThat(visionProperties.processingRegion()).isEqualTo("asia-northeast3");
		assertThat(visionProperties.dataRetention()).isZero();
		assertThat(visionProperties.costLimitUsd()).isEqualByComparingTo("9.50");
		assertThat(visionProperties.dailyJobLimit()).isEqualTo(99);
		assertThat(visionProperties.timeout()).isEqualTo(Duration.ofSeconds(4));
		assertThat(matchingProperties.radiusMin()).isEqualTo(501);
		assertThat(matchingProperties.radiusBase()).isEqualTo(1001);
		assertThat(matchingProperties.radiusMax()).isEqualTo(2999);
		assertThat(matchingProperties.radiusCoefficient()).isEqualByComparingTo(new BigDecimal("0.11"));
		assertThat(matchingProperties.radiusPolicyVersion()).isEqualTo("p0-test-v2");
		assertThat(matchingProperties.timeWindow()).isEqualTo(Duration.ofHours(23));
		assertThat(lostReportProperties.ttl()).isEqualTo(Duration.ofDays(13));
		assertThat(lostCenterProperties.nearbyRadius()).isEqualTo(999);
		assertThat(lostCenterProperties.nearbyLimit()).isEqualTo(9);
		System.out.println("P0_CONFIG_OBSERVABLE overridden-bindings=true clock-zone=Z live-adapters-enabled=false");
	}

	@Test
	void invalidMatchingRadiusOrderIsRejected() {
		// Given
		BigDecimal coefficient = new BigDecimal("0.10");

		// When
		Throwable thrown = catchThrowable(() -> new MatchingProperties(
			1001, 1000, 3000, coefficient, "p0-test-v2", Duration.ofHours(24)));

		// Then
		assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("matching radii must satisfy min <= base <= max");
		System.out.println("P0_CONFIG_OBSERVABLE invalid-radius-order=rejected");
	}

	@Test
	void visionTimeoutBindingAcceptsCeilingAndRejectsNonPositiveAndOverCeiling() {
		// Given
		ApplicationContextRunner valid = visionPropertiesRunner("PT10S");

		// When / Then
		valid.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(VisionProperties.class).timeout()).isEqualTo(Duration.ofSeconds(10));
		});
		visionPropertiesRunner("PT0S").run(context -> assertThat(context).hasFailed());
		visionPropertiesRunner("PT10.000000001S").run(context -> assertThat(context).hasFailed());
		System.out.println("P0_CONFIG_OBSERVABLE vision-timeout=PT10S accepted zero-and-over-ceiling=rejected");
	}

	@Test
	void visionDailyJobLimitRejectsNonPositiveValues() {
		visionPropertiesRunner("PT10S", "1").run(context -> assertThat(context).hasNotFailed());
		visionPropertiesRunner("PT10S", "0").run(context -> assertThat(context).hasFailed());
	}

	private ApplicationContextRunner visionPropertiesRunner(String timeout) {
		return visionPropertiesRunner(timeout, "100");
	}

	private ApplicationContextRunner visionPropertiesRunner(String timeout, String dailyJobLimit) {
		return new ApplicationContextRunner()
			.withUserConfiguration(VisionPropertiesBindingConfig.class)
			.withPropertyValues(
				"vision.enabled=false",
				"vision.provider=google-cloud-vision",
				"vision.processing-region=asia-northeast3",
				"vision.data-retention=PT0S",
				"vision.cost-limit-usd=9.50",
				"vision.daily-job-limit=" + dailyJobLimit,
				"vision.timeout=" + timeout);
	}

	private HttpRequest.Builder request(String path) {
		return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
	}

	private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
		JsonNode json = objectMapper.readTree(response.body());
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(json.propertyNames()).containsExactlyInAnyOrderElementsOf(ERROR_KEYS);
		assertThat(json.get("code").asString()).isEqualTo(code);
		assertThat(json.get("message").asString()).isNotBlank();
	}

	private void assertNoSecret(String responseBody, CapturedOutput output) {
		String encodedSecret = Base64.getEncoder().encodeToString(jwtProperties.secret());
		assertThat(responseBody).doesNotContain(encodedSecret);
		assertThat(output.getAll()).doesNotContain(encodedSecret);
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(VisionProperties.class)
	static class VisionPropertiesBindingConfig {
	}
}
