package kr.lostory.backend.point.domain;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("point")
public record PointPolicy(
		@Positive int signupGrant,
		@Positive int candidateAccessCost,
		@Positive int centerConfirmedReturnReward
) {
}
