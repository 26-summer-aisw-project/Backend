package kr.lostory.backend;

import kr.lostory.backend.point.domain.PointPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class PointPolicyConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(PointPolicyConfiguration.class);

	@Test
	void defaultsBindToDocumentedPolicy() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			PointPolicy policy = context.getBean(PointPolicy.class);
			assertThat(policy.signupGrant()).isEqualTo(10);
			assertThat(policy.candidateAccessCost()).isOne();
			assertThat(policy.centerConfirmedReturnReward()).isEqualTo(5);
			System.out.println("POINT_POLICY_DEFAULT_OBSERVABLE signup=10 candidate=1 return=5");
		});
	}

	@Test
	void customValuesBindExactly() {
		contextRunner
			.withPropertyValues(
				"POINT_SIGNUP_GRANT=12",
				"POINT_CANDIDATE_ACCESS_COST=2",
				"POINT_CENTER_CONFIRMED_RETURN_REWARD=7")
			.run(context -> {
				assertThat(context).hasNotFailed();
				PointPolicy policy = context.getBean(PointPolicy.class);
				assertThat(policy.signupGrant()).isEqualTo(12);
				assertThat(policy.candidateAccessCost()).isEqualTo(2);
				assertThat(policy.centerConfirmedReturnReward()).isEqualTo(7);
			});
	}

	@Test
	void zeroNegativeAndMalformedValuesFailStartup() {
		assertInvalid("POINT_SIGNUP_GRANT=0");
		assertInvalid("POINT_CANDIDATE_ACCESS_COST=-1");
		assertInvalid("POINT_CENTER_CONFIRMED_RETURN_REWARD=not-a-number");
		System.out.println("POINT_POLICY_INVALID_OBSERVABLE zero=failed negative=failed malformed=failed");
	}

	private void assertInvalid(String property) {
		contextRunner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PointPolicy.class)
	static class PointPolicyConfiguration {
	}
}
