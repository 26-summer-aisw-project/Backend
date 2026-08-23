package kr.lostory.backend.config;

import java.math.BigDecimal;
import java.time.Duration;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("matching")
public record MatchingProperties(
	@Min(1) int radiusMin,
	@Min(1) int radiusBase,
	@Min(1) int radiusMax,
	@NotNull @DecimalMin("0.0") BigDecimal radiusCoefficient,
	@NotBlank String radiusPolicyVersion,
	@NotNull @DurationMin(nanos = 1) Duration timeWindow
) {
	public MatchingProperties {
		if (radiusMin > radiusBase || radiusBase > radiusMax) {
			throw new IllegalArgumentException("matching radii must satisfy min <= base <= max");
		}
	}
}
