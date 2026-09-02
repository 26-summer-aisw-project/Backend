package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.point.domain.PointPolicy;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class CandidateAccessPolicyTemporalIntegrationTest {

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse("postgis/postgis:16-3.5-alpine").asCompatibleSubstituteFor("postgres"));

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void firstAccessUnderPolicyAReplaysImmutableResultUnderPolicyB() throws Exception {
		UUID firstKey = UUID.randomUUID();
		long userId;
		long reportId;
		String token;
		Observed first;

		try (ConfigurableApplicationContext contextA = context(1)) {
			assertThat(contextA.getBean(PointPolicy.class).candidateAccessCost()).isOne();
			JdbcTemplate jdbc = contextA.getBean(JdbcTemplate.class);
			User user = contextA.getBean(UserRepository.class)
					.saveAndFlush(new User(UUID.randomUUID() + "@policy-temporal.test", "hash"));
			userId = user.getId();
			jdbc.update("INSERT INTO point_accounts (user_id, balance) VALUES (?, 10)", userId);
			reportId = report(jdbc, userId);
			token = contextA.getBean(JwtTokenService.class).issue(user).value();
			first = curl(contextA, reportId, token, firstKey);
			assertResult(first, 1, 9, false);
		}

		try (ConfigurableApplicationContext contextB = context(2)) {
			assertThat(contextB.getBean(PointPolicy.class).candidateAccessCost()).isEqualTo(2);
			Observed same = curl(contextB, reportId, token, firstKey);
			Observed different = curl(contextB, reportId, token, UUID.randomUUID());
			assertResult(same, 1, 9, true);
			assertResult(different, 1, 9, true);

			JdbcTemplate jdbc = contextB.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForObject("SELECT balance FROM point_accounts WHERE user_id = ?",
					Integer.class, userId)).isEqualTo(9);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_accesses WHERE report_id = ?",
					Integer.class, reportId)).isOne();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE user_id = ? "
					+ "AND entry_type = 'CANDIDATE_ACCESS_DEBIT' AND reference_id = ?",
					Integer.class, userId, reportId)).isOne();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_idempotency_receipts "
					+ "WHERE user_id = ? AND report_id = ?", Integer.class, userId, reportId)).isEqualTo(2);
		}

		System.out.println("CANDIDATE_POLICY_TEMPORAL_CURL_OBSERVABLE command=curl -sS -i --max-time 15 "
				+ "-H 'Authorization: Bearer <REDACTED_BEARER>' "
				+ "-H 'Idempotency-Key: <REDACTED_UUID>' -X POST "
				+ "'http://127.0.0.1:<RANDOM_PORT>/api/v1/lost-reports/<REPORT_ID>/candidate-accesses' "
				+ "policy=1->2 statuses=200,200,200 replayed=false,true,true debit=1,1,1 balance=9,9,9 "
				+ "candidateDebit=1 access=1 receipts=2 account=9");
	}

	private ConfigurableApplicationContext context(int candidateAccessCost) {
		return new SpringApplicationBuilder(BackendApplication.class, TestObjectStorageConfiguration.class)
				.profiles("test")
				.run(
						"--server.port=0",
						"--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
						"--spring.datasource.username=" + POSTGRES.getUsername(),
						"--spring.datasource.password=" + POSTGRES.getPassword(),
						"--point.candidate-access-cost=" + candidateAccessCost,
						"--vision.worker-initial-delay=PT1H",
						"--spring.main.allow-bean-definition-overriding=true");
	}

	private long report(JdbcTemplate jdbc, long ownerId) {
		Long reportId = jdbc.queryForObject("""
				INSERT INTO lost_reports
				    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
				     effective_search_radius_meters, radius_policy_version, center_guidance,
				     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
				VALUES (?, 'WALLET', now() - interval '2 hours', now(), 'wallet', 1000, 1000,
				        'p0-radius-v1', '[]', true, 'p0-matching-v1', 'OPEN', now() + interval '1 day', now(), now())
				RETURNING id
				""", Long.class, ownerId);
		jdbc.update("INSERT INTO report_waypoints (report_id, ordinal, location, created_at) VALUES "
				+ "(?, 1, ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography, now())", reportId);
		return reportId;
	}

	private Observed curl(ConfigurableApplicationContext context, long reportId, String token, UUID key)
			throws Exception {
		int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
		Process process = new ProcessBuilder("curl", "-sS", "-i", "--max-time", "15",
				"-H", "Authorization: Bearer " + token,
				"-H", "Idempotency-Key: " + key,
				"-X", "POST", "http://127.0.0.1:" + port + "/api/v1/lost-reports/" + reportId
						+ "/candidate-accesses").redirectErrorStream(true).start();
		if (!process.waitFor(20, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("forced curl cleanup").isTrue();
			throw new AssertionError("curl exceeded the bounded timeout");
		}
		String transcript = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.exitValue()).isZero();
		int separator = transcript.lastIndexOf("\r\n\r\n");
		int delimiterLength = 4;
		if (separator < 0) {
			separator = transcript.lastIndexOf("\n\n");
			delimiterLength = 2;
		}
		int status = Integer.parseInt(transcript.lines().findFirst().orElseThrow().split(" ")[1]);
		return new Observed(status, transcript.substring(separator + delimiterLength));
	}

	private void assertResult(Observed observed, int debit, int balance, boolean replayed) throws Exception {
		assertThat(observed.status()).isEqualTo(200);
		JsonNode body = json.readTree(observed.body());
		assertThat(body.get("debitedPoints").isIntegralNumber()).isTrue();
		assertThat(body.get("debitedPoints").asInt()).isEqualTo(debit);
		assertThat(body.get("remainingBalance").isIntegralNumber()).isTrue();
		assertThat(body.get("remainingBalance").asInt()).isEqualTo(balance);
		assertThat(body.get("replayed").asBoolean()).isEqualTo(replayed);
	}

	@Configuration(proxyBeanMethods = false)
	static class TestObjectStorageConfiguration {
		@Bean
		@Primary
		ObjectStorage objectStorage() {
			return mock(ObjectStorage.class);
		}
	}

	private record Observed(int status, String body) {
	}
}
