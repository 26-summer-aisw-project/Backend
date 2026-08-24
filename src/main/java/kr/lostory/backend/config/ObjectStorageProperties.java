package kr.lostory.backend.config;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("object-storage")
public record ObjectStorageProperties(
	boolean enabled,
	@NotNull URI endpoint,
	@NotBlank String region,
	@NotBlank String bucket,
	boolean pathStyle,
	@NotNull @DurationMin(nanos = 1) Duration timeout,
	@NotNull @DurationMin(nanos = 1) Duration orphanGrace,
	@NotNull @DurationMin(nanos = 1) Duration orphanSweepInterval,
	@NotNull @DurationMin(nanos = 1) Duration orphanSweepInitialDelay
) {
}
