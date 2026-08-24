package kr.lostory.backend.config;

import java.math.BigDecimal;
import java.time.Duration;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.hibernate.validator.constraints.time.DurationMax;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("vision")
public record VisionProperties(
	boolean enabled,
	@NotBlank String provider,
	@NotBlank String processingRegion,
	@NotNull Duration dataRetention,
	@NotNull @DecimalMin("0.0") BigDecimal costLimitUsd,
	@Positive int dailyJobLimit,
	@NotNull @DurationMin(nanos = 1) @DurationMax(seconds = 10) Duration timeout
) {
}
