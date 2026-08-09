package kr.lostory.backend;

import java.time.Duration;

import kr.lostory.backend.config.JwtConfiguration;
import kr.lostory.backend.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class JwtConfigurationTest {

	private static final String VALID_SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQtMzItYnl0ZXMtbG9uZw==";

	@Test
	void validConfigurationStartsAndBcryptMatches() {
		new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues("spring.profiles.active=test")
			.withUserConfiguration(JwtConfiguration.class)
			.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(JwtProperties.class).accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
			PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
			assertThat(encoder.matches("password", encoder.encode("password"))).isTrue();
			});
	}

	@Test
	void missingAndBlankIssuerFailStartup() {
		assertRejected(new ApplicationContextRunner()
			.withUserConfiguration(JwtConfiguration.class)
			.withPropertyValues("app.jwt.secret=" + VALID_SECRET, "app.jwt.access-token-ttl=PT15M"), "issuer", null);
		assertRejected(validRunner().withPropertyValues("app.jwt.issuer="), "issuer", null);
	}

	@Test
	void malformedSecretsFailStartupWithoutDisclosure() {
		assertRejected(new ApplicationContextRunner()
			.withUserConfiguration(JwtConfiguration.class)
			.withPropertyValues("app.jwt.issuer=https://issuer.test.invalid", "app.jwt.access-token-ttl=PT15M"), "secret", null);
		assertRejected(validRunner().withPropertyValues("app.jwt.secret=not-base64!"), "Base64", "not-base64!");
		assertRejected(validRunner().withPropertyValues("app.jwt.secret=MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMQ=="), "32", null);
	}

	@Test
	void nonPositiveAndOverlongTtlFailStartup() {
		assertRejected(validRunner().withPropertyValues("app.jwt.access-token-ttl=PT0S"), "greater than 0", null);
		assertRejected(validRunner().withPropertyValues("app.jwt.access-token-ttl=-PT1S"), "greater than 0", null);
		assertRejected(validRunner().withPropertyValues("app.jwt.access-token-ttl=PT24H0.000000001S"), "24 hours", null);
	}

	private ApplicationContextRunner validRunner() {
		return new ApplicationContextRunner()
			.withUserConfiguration(JwtConfiguration.class)
			.withPropertyValues(
				"app.jwt.issuer=https://issuer.test.invalid",
				"app.jwt.secret=" + VALID_SECRET,
				"app.jwt.access-token-ttl=PT15M");
	}

	private void assertRejected(ApplicationContextRunner runner, String reason, String exposedSecret) {
		runner.run((context) -> {
			assertThat(context).hasFailed();
			String messages = allMessages(context.getStartupFailure());
			assertThat(messages).containsIgnoringCase(reason);
			if (exposedSecret != null) {
				assertThat(messages).doesNotContain(exposedSecret);
			}
		});
	}

	private String allMessages(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			messages.append(current.getMessage()).append('\n');
		}
		return messages.toString();
	}
}
