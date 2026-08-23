package kr.lostory.backend.config;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lost-report")
public record LostReportProperties(@NotNull @DurationMin(nanos = 1) Duration ttl) {
}
