package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class Task6HttpAssertions {

	private final ObjectMapper json;
	private final JdbcTemplate jdbc;

	Task6HttpAssertions(ObjectMapper json, JdbcTemplate jdbc) {
		this.json = json;
		this.jdbc = jdbc;
	}

	void access(HttpResponse<String> response, AccessExpected expected) throws Exception {
		assertThat(response.statusCode()).isEqualTo(200);
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder(
				"reportId", "unlockedAt", "debitedPoints", "remainingBalance", "replayed");
		assertThat(body.get("reportId").asString()).isEqualTo(Long.toString(expected.reportId()));
		assertThat(body.get("debitedPoints").asInt()).isOne();
		assertThat(body.get("remainingBalance").asInt()).isEqualTo(expected.balance());
		assertThat(body.get("replayed").asBoolean()).isEqualTo(expected.replayed());
	}

	void error(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
	}

	void concurrentResponses(HttpResponse<String> first, HttpResponse<String> second) throws Exception {
		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(List.of(json.readTree(first.body()).get("replayed").asBoolean(),
				json.readTree(second.body()).get("replayed").asBoolean()))
				.containsExactlyInAnyOrder(false, true);
	}

	void concurrentState(ConcurrentExpected expected) {
		long accessId = jdbc.queryForObject("SELECT id FROM candidate_accesses WHERE report_id = ?",
				Long.class, expected.reportId());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_accesses WHERE report_id = ?",
				Integer.class, expected.reportId())).isOne();
		assertThat(jdbc.queryForList("SELECT idempotency_key FROM candidate_access_idempotency_receipts "
				+ "WHERE report_id = ? AND user_id = ? AND candidate_access_id = ?", UUID.class,
				expected.reportId(), expected.userId(), accessId))
				.containsExactlyInAnyOrder(expected.firstKey(), expected.secondKey());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE user_id = ? AND amount = -1 "
				+ "AND entry_type = 'CANDIDATE_ACCESS_DEBIT' AND reference_type = 'LOST_REPORT' "
				+ "AND reference_id = ?", Integer.class, expected.userId(), expected.reportId())).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_accesses access "
				+ "JOIN point_ledger ledger ON ledger.id = access.debit_transaction_id "
				+ "WHERE access.id = ? AND ledger.user_id = ? AND ledger.amount = -1 "
				+ "AND ledger.entry_type = 'CANDIDATE_ACCESS_DEBIT' "
				+ "AND ledger.reference_type = 'LOST_REPORT' AND ledger.reference_id = ?",
				Integer.class, accessId, expected.userId(), expected.reportId())).isOne();
		assertThat(jdbc.queryForObject("SELECT balance FROM point_accounts WHERE user_id = ?",
				Integer.class, expected.userId())).isEqualTo(9);
	}

	record AccessExpected(long reportId, int balance, boolean replayed) {
	}

	record ConcurrentExpected(long reportId, long userId, UUID firstKey, UUID secondKey) {
	}
}
