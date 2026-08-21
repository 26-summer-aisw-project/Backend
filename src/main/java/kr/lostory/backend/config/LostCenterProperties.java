package kr.lostory.backend.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lost-center")
public record LostCenterProperties(@Min(1) int nearbyLimit) {
}
