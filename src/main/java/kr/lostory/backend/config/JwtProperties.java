package kr.lostory.backend.config;

import java.time.Duration;
import java.util.Base64;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.jwt")
public final class JwtProperties {

	@NotBlank
	private final String issuer;

	@Size(min = 32)
	private final byte[] secret;

	@NotNull
	@DurationMin(nanos = 1, message = "must be greater than 0")
	@DurationMax(hours = 24, message = "must be at most 24 hours")
	private final Duration accessTokenTtl;

	public JwtProperties(String issuer, String secret, Duration accessTokenTtl) {
		this.issuer = issuer;
		this.secret = decodeSecret(secret);
		this.accessTokenTtl = accessTokenTtl;
	}

	public String issuer() {
		return issuer;
	}

	public byte[] secret() {
		return secret.clone();
	}

	public Duration accessTokenTtl() {
		return accessTokenTtl;
	}

	private static byte[] decodeSecret(String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalArgumentException("JWT secret is required");
		}
		try {
			return Base64.getDecoder().decode(secret);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("JWT secret must be valid Base64", exception);
		}
	}
}
