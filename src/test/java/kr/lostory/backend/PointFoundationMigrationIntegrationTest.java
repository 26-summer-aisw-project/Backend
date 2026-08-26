package kr.lostory.backend;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PointFoundationMigrationIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
		DockerImageName.parse("postgis/postgis:16-3.5-alpine").asCompatibleSubstituteFor("postgres")
	);

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void startPostgres() {
		POSTGRES.start();
		dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
	}

	@AfterAll
	static void stopPostgres() {
		POSTGRES.stop();
	}

	@Test
	void v28BackfillsOnlyActiveOrdinaryUsersOnceAndPreservesLegacyNullReferences() {
		// Given
		migrateToV27();
		insertUsersAndLegacyLedger();

		// When
		migrateLatest();
		migrateLatest();

		// Then
		assertThat(jdbc.queryForList(
			"SELECT u.id || ':' || a.balance FROM users u JOIN point_accounts a ON a.user_id = u.id ORDER BY u.id",
			String.class
		)).containsExactly("101:13");
		assertThat(jdbc.queryForList(
			"SELECT user_id || ':' || amount || ':' || idempotency_key FROM point_ledger "
				+ "WHERE entry_type = 'SIGNUP_GRANT' ORDER BY user_id",
			String.class
		)).containsExactly("101:10:" + jdbc.queryForObject(
			"SELECT md5('lostory:signup-grant:v28:' || 101)::uuid",
			String.class
		));
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM point_ledger WHERE entry_type = 'SIGNUP_GRANT' AND user_id IN (102, 103, 104)",
			Integer.class
		)).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM point_ledger WHERE id IN (201, 202, 203) "
				+ "AND reference_type IS NULL AND reference_id IS NULL",
			Integer.class
		)).isEqualTo(3);
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO point_ledger (user_id, entry_type, amount, idempotency_key) "
				+ "VALUES (101, 'SIGNUP_GRANT', 10, '00000000-0000-0000-0000-000000000099')"
		)).hasRootCauseInstanceOf(java.sql.SQLException.class);
		System.out.println("POINT_V28_BACKFILL_OBSERVABLE active-user=1 excluded=3 legacy-null=3 duplicate-grant=rejected");
	}

	@Test
	void v28EnforcesNewReferencesAndDetectsReceiptReuseAcrossUsersAndReports() {
		// Given
		migrateToV27();
		insertUsersAndLegacyLedger();
		migrateLatest();
		insertReport(1001, 101);
		insertReport(1002, 102);
		jdbc.update("INSERT INTO point_accounts (user_id, balance) VALUES (102, 10)");

		// When
		long firstAccess = insertCandidateAccess(1001, 101, 301, "00000000-0000-0000-0000-000000000031");
		long secondAccess = insertCandidateAccess(1002, 102, 302, "00000000-0000-0000-0000-000000000032");
		jdbc.update(
			"INSERT INTO candidate_access_idempotency_receipts "
				+ "(idempotency_key, user_id, report_id, candidate_access_id) VALUES (?::uuid, ?, ?, ?)",
			"00000000-0000-0000-0000-000000000041", 101, 1001, firstAccess
		);

		// Then
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO candidate_access_idempotency_receipts "
				+ "(idempotency_key, user_id, report_id, candidate_access_id) VALUES (?::uuid, ?, ?, ?)",
			"00000000-0000-0000-0000-000000000041", 102, 1002, secondAccess
		)).hasRootCauseInstanceOf(java.sql.SQLException.class);
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO point_ledger (user_id, entry_type, amount, idempotency_key) "
				+ "VALUES (101, 'CANDIDATE_ACCESS_DEBIT', -1, '00000000-0000-0000-0000-000000000051')"
		)).hasRootCauseInstanceOf(java.sql.SQLException.class);
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO point_ledger (user_id, entry_type, amount, idempotency_key) "
				+ "VALUES (101, 'CENTER_RETURN_REWARD', 5, '00000000-0000-0000-0000-000000000052')"
		)).hasRootCauseInstanceOf(java.sql.SQLException.class);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_idempotency_receipts", Integer.class)).isOne();
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO candidate_access_idempotency_receipts "
				+ "(idempotency_key, user_id, report_id, candidate_access_id) VALUES (?::uuid, ?, ?, ?)",
			"00000000-0000-0000-0000-000000000042", 101, 1001, Long.MAX_VALUE
		)).hasRootCauseInstanceOf(java.sql.SQLException.class);
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO candidate_access_idempotency_receipts "
				+ "(idempotency_key, user_id, report_id, candidate_access_id) VALUES (?::uuid, ?, ?, ?)",
			"00000000-0000-0000-0000-000000000043", 102, 1002, firstAccess
		)).hasRootCauseInstanceOf(java.sql.SQLException.class);
		System.out.println("POINT_V28_RECEIPT_OBSERVABLE first-receipt=durable cross-user-report-reuse=rejected null-reference=rejected");
	}

	private static void migrateToV27() {
		Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).target("27").load();
		flyway.clean();
		flyway.migrate();
	}

	private static void migrateLatest() {
		Flyway.configure().dataSource(dataSource).load().migrate();
	}

	private static void insertUsersAndLegacyLedger() {
		jdbc.execute("""
			INSERT INTO users (id, email, password_hash, display_name, status, role, created_at, updated_at) VALUES
				(101, 'active-user@example.test', 'hash', 'Active User', 'ACTIVE', 'USER', NOW(), NOW()),
				(102, 'manager@example.test', 'hash', 'Manager', 'ACTIVE', 'CENTER_MANAGER', NOW(), NOW()),
				(103, 'blocked@example.test', 'hash', 'Blocked', 'BLOCKED', 'USER', NOW(), NOW()),
				(104, 'deleted@example.test', 'hash', 'Deleted', 'DELETED', 'USER', NOW(), NOW());
			INSERT INTO point_accounts (user_id, balance) VALUES (101, 3);
			INSERT INTO point_ledger (id, user_id, entry_type, amount, idempotency_key, reason) VALUES
				(201, 101, 'DEMO_GRANT', 3, '00000000-0000-0000-0000-000000000021', NULL),
				(202, 101, 'ADMIN_ADJUSTMENT', 1, '00000000-0000-0000-0000-000000000022', 'legacy'),
				(203, 101, 'CANDIDATE_ACCESS_DEBIT', -1, '00000000-0000-0000-0000-000000000023', NULL);
			""");
	}

	private static void insertReport(long reportId, long userId) {
		jdbc.update("""
			INSERT INTO lost_reports (
				id, reporter_id, category, lost_at_from, lost_at_to, description, search_radius, status,
				expired_at, created_at, updated_at, effective_search_radius_meters, radius_policy_version,
				center_guidance, candidates_stale, matching_policy_version
			) VALUES (?, ?, 'WALLET', NOW() - INTERVAL '1 day', NOW(), 'fixture', 1000, 'OPEN',
				NOW() + INTERVAL '14 days', NOW(), NOW(), 1000, 'fixture', '[]'::jsonb, true, 'fixture')
			""", reportId, userId);
	}

	private static long insertCandidateAccess(long reportId, long userId, long ledgerId, String idempotencyKey) {
		jdbc.update(
			"INSERT INTO point_ledger (id, user_id, entry_type, amount, idempotency_key, reference_type, reference_id) "
				+ "VALUES (?, ?, 'CANDIDATE_ACCESS_DEBIT', -1, ?::uuid, 'LOST_REPORT', ?)",
			ledgerId, userId, idempotencyKey, reportId
		);
		return jdbc.queryForObject(
			"INSERT INTO candidate_accesses (report_id, user_id, debit_transaction_id) VALUES (?, ?, ?) RETURNING id",
			Long.class,
			reportId, userId, ledgerId
		);
	}
}
