package kr.lostory.backend;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

	@Test
	void emptyV26DatabaseMigratesThroughV32WithoutFabricatingRows() {
		// Given
		migrateToV26();

		// When
		migrateLatest();

		// Then
		assertThat(jdbc.queryForList(
			"SELECT version FROM flyway_schema_history WHERE version IN ('27', '28', '29', '30', '31', '32') "
				+ "ORDER BY installed_rank",
			String.class
		)).containsExactly("27", "28", "29", "30", "31", "32");
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN "
				+ "('point_accounts', 'point_ledger', 'candidate_access_idempotency_receipts', "
				+ "'center_partnerships', 'center_activation_tokens', 'center_handovers', 'return_records')",
			Integer.class
		)).isEqualTo(7);
		assertThat(jdbc.queryForObject(
			"SELECT (SELECT count(*) FROM users) + (SELECT count(*) FROM point_accounts) "
				+ "+ (SELECT count(*) FROM point_ledger) + (SELECT count(*) FROM center_partnerships) "
				+ "+ (SELECT count(*) FROM center_activation_tokens) + (SELECT count(*) FROM center_handovers) "
				+ "+ (SELECT count(*) FROM return_records)",
			Integer.class
		)).isZero();
		System.out.println("POINT_V26_EMPTY_OBSERVABLE versions=27,28,29,30,31,32 required-tables=7 fabricated-rows=0");
	}

	@Test
	void originalV30AppliedHistoryValidatesAndMigratesThroughV32(@TempDir Path migrationDirectory)
		throws IOException {
		// Given
		migrateCleanTo("29");
		Path historicalV30 = migrationDirectory.resolve("V30__create_center_handovers.sql");
		var historicalV30Stream = getClass().getResourceAsStream(
			"/migration-history/V30__create_center_handovers.sql"
		);
		assertThat(historicalV30Stream).as("historical V30 migration fixture").isNotNull();
		try (historicalV30Stream) {
			Files.copy(historicalV30Stream, historicalV30);
		}
		Flyway.configure()
			.dataSource(dataSource)
			.locations("filesystem:" + migrationDirectory.toAbsolutePath())
			.ignoreMigrationPatterns("*:missing")
			.target("30")
			.load()
			.migrate();

		// When
		migrateLatest();

		// Then
		assertThat(jdbc.queryForList(
			"SELECT version || ':' || checksum || ':' || success FROM flyway_schema_history "
				+ "WHERE version IN ('30', '31', '32') ORDER BY installed_rank",
			String.class
		)).containsExactly("30:-836339116:true", "31:-794504958:true", "32:-1086288806:true");
		System.out.println("POINT_V30_HISTORY_OBSERVABLE applied-checksum=-836339116 versions=30,31,32-success");
	}

	@Test
	void v32AcceptsExpiredPendingCenterHandoverWithoutFabricatingArtifacts() {
		// Given
		migrateToV26();
		migrateTo("31");
		insertPreV32ExpiredPendingCenterHandoverFixture();

		// When
		migrateLatest();

		// Then
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM flyway_schema_history WHERE version = '32' AND success",
			Integer.class
		)).isOne();
		assertThat(jdbc.queryForObject("""
			SELECT status = 'EXPIRED'
				AND storage_method = 'HANDED_TO_CENTER'
				AND storage_description IS NULL
				AND center_id IS NOT NULL
				AND legacy_handover_place_name IS NULL
				AND handover_status = 'NONE'
				AND handed_at IS NULL
			FROM found_items
			WHERE name = 'v32 expired pending fixture'
			""", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM center_partnerships", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM center_activation_tokens", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM center_handovers", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM return_records", Integer.class)).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM point_ledger WHERE entry_type = 'CENTER_RETURN_REWARD'",
			Integer.class
		)).isZero();
		assertThat(jdbc.queryForObject("""
			SELECT count(*)
			FROM found_item_images
			WHERE found_item_id = (SELECT id FROM found_items WHERE name = 'v32 expired pending fixture')
			""", Integer.class)).isZero();
		System.out.println("POINT_V32_EXPIRED_PENDING_OBSERVABLE history=32-success fixture=preserved "
			+ "partnerships=0 tokens=0 handovers=0 returns=0 rewards=0 images=0");
	}

	@Test
	void representativeV26LegacyDataMigratesConservativelyAndRerunsAsNoOp() {
		// Given
		migrateToV26();
		insertV26LegacyFixture();
		migrateTo("27");
		insertV27ManagerFixture();

		// When
		migrateLatest();

		// Then
		assertThat(jdbc.queryForList(
			"SELECT u.id || ':' || a.balance FROM users u JOIN point_accounts a ON a.user_id = u.id ORDER BY u.id",
			String.class
		)).containsExactly("101:13");
		assertThat(jdbc.queryForList(
			"SELECT user_id || ':' || amount FROM point_ledger WHERE entry_type = 'SIGNUP_GRANT' ORDER BY user_id",
			String.class
		)).containsExactly("101:10");
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM point_ledger WHERE entry_type = 'SIGNUP_GRANT' AND user_id IN (102, 103, 104)",
			Integer.class
		)).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM point_ledger WHERE id IN (201, 202, 203) "
				+ "AND reference_type IS NULL AND reference_id IS NULL",
			Integer.class
		)).isEqualTo(3);
		assertThat(jdbc.queryForList(
			"SELECT found_item_id || ':' || center_id || ':' || status FROM center_handovers ORDER BY found_item_id",
			String.class
		)).containsExactly("301:201:USER_CONFIRMED");
		assertThat(jdbc.queryForObject(
			"SELECT legacy_storage_path || ':' || coalesce(object_key, 'null') || ':' || is_current "
				+ "FROM found_item_images WHERE id = 401",
			String.class
		)).isEqualTo("legacy/items/302.jpg:null:false");
		assertThat(jdbc.queryForObject(
			"SELECT (SELECT count(*) FROM center_partnerships) + (SELECT count(*) FROM center_activation_tokens) "
				+ "+ (SELECT count(*) FROM return_records) + (SELECT count(*) FROM lost_reports) "
				+ "+ (SELECT count(*) FROM found_item_vision_jobs) + (SELECT count(*) FROM object_deletion_outbox) "
				+ "+ (SELECT count(*) FROM point_ledger WHERE entry_type = 'CENTER_RETURN_REWARD')",
			Integer.class
		)).isZero();
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO point_ledger (user_id, entry_type, amount, idempotency_key) "
				+ "VALUES (101, 'CANDIDATE_ACCESS_DEBIT', -1, '00000000-0000-0000-0000-000000000099')"
		)).hasRootCauseInstanceOf(java.sql.SQLException.class);

		String beforeRerun = migrationStateSnapshot();
		migrateLatest();
		assertThat(migrationStateSnapshot()).isEqualTo(beforeRerun);
		System.out.println("POINT_V26_LEGACY_OBSERVABLE active-grant=1 excluded=3 legacy-null=3 "
			+ "explicit-handover=1 ambiguous-handover=0 inferred-artifacts=0 rerun=no-op malformed-reference=rejected");
	}

	@Test
	void laterTransactionalMigrationFailureLeavesNoResidueAndRecoversExactlyOnce(@TempDir Path migrationDirectory)
		throws IOException {
		// Given
		migrateToV26();
		migrateLatest();
		writeMigration(migrationDirectory, "V33__test_failure.sql", """
			CREATE TABLE migration_failure_probe (id BIGINT PRIMARY KEY);
			INSERT INTO migration_failure_probe (id) VALUES (1);
			INSERT INTO vision_daily_admissions (admission_date, reserved_count) VALUES (DATE '2099-01-01', 1);
			SELECT 1 / 0;
			""");

		// When / Then
		assertThatThrownBy(() -> migrateLatestWith(migrationDirectory))
			.hasRootCauseInstanceOf(java.sql.SQLException.class);
		assertThat(jdbc.queryForObject("SELECT to_regclass('public.migration_failure_probe')", String.class)).isNull();
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM vision_daily_admissions WHERE admission_date = DATE '2099-01-01'",
			Integer.class
		)).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM flyway_schema_history WHERE version = '33'",
			Integer.class
		)).isZero();

		writeMigration(migrationDirectory, "V33__test_failure.sql", """
			CREATE TABLE migration_recovery_probe (id BIGINT PRIMARY KEY);
			INSERT INTO migration_recovery_probe (id) VALUES (1);
			""");
		migrateLatestWith(migrationDirectory);
		migrateLatestWith(migrationDirectory);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM migration_recovery_probe", Integer.class)).isOne();
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM flyway_schema_history WHERE version = '33' AND success",
			Integer.class
		)).isOne();
		System.out.println("POINT_FAILURE_RECOVERY_OBSERVABLE residue=0 recovered-version=33 applied=1 rerun=no-op");
	}

	private static void migrateToV27() {
		Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).target("27").load();
		flyway.clean();
		flyway.migrate();
	}

	private static void migrateToV26() {
		Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).target("26").load();
		flyway.clean();
		flyway.migrate();
	}

	private static void migrateCleanTo(String target) {
		Flyway flyway = Flyway.configure().dataSource(dataSource).cleanDisabled(false).target(target).load();
		flyway.clean();
		flyway.migrate();
	}

	private static void migrateTo(String target) {
		Flyway.configure().dataSource(dataSource).target(target).load().migrate();
	}

	private static void migrateLatest() {
		Flyway.configure().dataSource(dataSource).load().migrate();
	}

	private static void migrateLatestWith(Path migrationDirectory) {
		Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration", "filesystem:" + migrationDirectory.toAbsolutePath())
			.load()
			.migrate();
	}

	private static void writeMigration(Path directory, String filename, String sql) throws IOException {
		Files.writeString(directory.resolve(filename), sql);
	}

	private static String migrationStateSnapshot() {
		return jdbc.queryForObject(
			"SELECT (SELECT count(*) FROM flyway_schema_history WHERE version BETWEEN '27' AND '31') || ':' "
				+ "|| (SELECT count(*) FROM point_accounts) || ':' || (SELECT count(*) FROM point_ledger) || ':' "
				+ "|| (SELECT count(*) FROM center_handovers) || ':' || (SELECT count(*) FROM return_records)",
			String.class
		);
	}

	private static void insertPreV32ExpiredPendingCenterHandoverFixture() {
		jdbc.execute("""
			ALTER TABLE found_items
				DROP CONSTRAINT found_items_storage_detail_check,
				ADD CONSTRAINT found_items_storage_detail_check CHECK (
					(status = 'DRAFT'
						AND storage_method IS NULL
						AND storage_description IS NULL
						AND legacy_handover_place_name IS NULL
						AND center_id IS NULL
						AND handover_status = 'NONE'
						AND handed_at IS NULL)
					OR
					(storage_method = 'LEFT_IN_PLACE'
						AND status IN ('ACTIVE', 'EXPIRED', 'RETURNED')
						AND storage_description IS NULL
						AND legacy_handover_place_name IS NULL
						AND center_id IS NULL
						AND handover_status = 'NONE'
						AND handed_at IS NULL)
					OR
					(storage_method = 'MOVED_TO_SAFE_PLACE'
						AND status IN ('ACTIVE', 'EXPIRED', 'RETURNED')
						AND storage_description IS NOT NULL
						AND btrim(storage_description) <> ''
						AND legacy_handover_place_name IS NULL
						AND center_id IS NULL
						AND handover_status = 'NONE'
						AND handed_at IS NULL)
					OR
					(storage_method = 'HANDED_TO_CENTER'
						AND storage_description IS NULL
						AND (
							(status = 'PENDING_HANDOVER'
								AND center_id IS NOT NULL
								AND legacy_handover_place_name IS NULL
								AND handover_status = 'NONE'
								AND handed_at IS NULL)
							OR
							(status IN ('ACTIVE', 'EXPIRED', 'RETURNED')
								AND center_id IS NOT NULL
								AND legacy_handover_place_name IS NULL
								AND handover_status IN ('USER_CONFIRMED', 'CENTER_CONFIRMED')
								AND handed_at IS NOT NULL
								AND handed_at BETWEEN created_at AND updated_at)
							OR
							(status IN ('ACTIVE', 'EXPIRED', 'RETURNED')
								AND center_id IS NULL
								AND legacy_handover_place_name IS NOT NULL
								AND btrim(legacy_handover_place_name) <> ''
								AND handover_status = 'LEGACY_UNVERIFIED'
								AND handed_at IS NULL)
						))
					OR
					(name = 'v32 expired pending fixture'
						AND status = 'EXPIRED'
						AND storage_method = 'HANDED_TO_CENTER'
						AND storage_description IS NULL
						AND center_id IS NOT NULL
						AND legacy_handover_place_name IS NULL
						AND handover_status = 'NONE'
						AND handed_at IS NULL)
				);
			INSERT INTO users (email, password_hash, display_name, status, role, created_at, updated_at)
			VALUES ('v32-fixture@example.test', 'hash', 'V32 Fixture', 'ACTIVE', 'USER',
				'2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');
			INSERT INTO lost_centers (
				source_key, name, address, location, contact_phone, operating_hours, is_active,
				verification_status, is_csv_managed, created_at, updated_at
			) VALUES (
				'fixture:center-v32', 'V32 Fixture Center', 'Fixture Address',
				ST_SetSRID(ST_MakePoint(126.95, 37.49), 4326)::geography, '000-redacted', 'fixture-hours',
				true, 'official_verified', false, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z'
			);
			INSERT INTO found_items (
				finder_id, name, category, description, found_at, storage_method, center_id,
				legacy_handover_place_name, handover_status, handed_at, status, vision_status,
				analysis_generation, created_at, updated_at, expired_at
			) VALUES (
				(SELECT id FROM users WHERE email = 'v32-fixture@example.test'), 'v32 expired pending fixture',
				'WALLET', 'fixture', '2026-08-02T00:00:00Z', 'HANDED_TO_CENTER',
				(SELECT id FROM lost_centers WHERE source_key = 'fixture:center-v32'), NULL, 'NONE', NULL,
				'EXPIRED', 'FAILED', 0, '2026-08-02T00:00:00Z', '2026-08-03T00:00:00Z',
				'2026-08-02T12:00:00Z'
			);
			""");
	}

	private static void insertV26LegacyFixture() {
		jdbc.execute("""
			INSERT INTO users (id, email, password_hash, display_name, status, role, created_at, updated_at) VALUES
				(101, 'active-user@example.test', 'hash', 'Active User', 'ACTIVE', 'USER', NOW(), NOW()),
				(103, 'blocked@example.test', 'hash', 'Blocked', 'BLOCKED', 'USER', NOW(), NOW()),
				(104, 'deleted@example.test', 'hash', 'Deleted', 'DELETED', 'USER', NOW(), NOW());
			INSERT INTO lost_centers (
				id, source_key, name, address, location, contact_phone, operating_hours, is_active,
				verification_status, is_csv_managed, created_at, updated_at
			) VALUES (
				201, 'fixture:center', 'Fixture Center', 'Fixture Address',
				ST_SetSRID(ST_MakePoint(126.95, 37.49), 4326)::geography, '000-redacted', 'fixture-hours',
				true, 'official_verified', false, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z'
			);
			INSERT INTO found_items (
				id, finder_id, name, category, description, found_at, storage_method, center_id,
				legacy_handover_place_name, handover_status, handed_at, status, vision_status,
				analysis_generation, created_at, updated_at, expired_at
			) VALUES
				(301, 101, 'explicit item', 'WALLET', 'explicit confirmation', '2026-08-02T00:00:00Z',
				 'HANDED_TO_CENTER', 201, NULL, 'USER_CONFIRMED', '2026-08-02T01:00:00Z', 'ACTIVE',
				 'FAILED', 0, '2026-08-02T00:00:00Z', '2026-08-02T02:00:00Z', '2026-08-16T00:00:00Z'),
				(302, 101, 'ambiguous item', 'WALLET', 'legacy desk only', '2026-08-02T00:00:00Z',
				 'HANDED_TO_CENTER', NULL, 'Unverified legacy desk', 'LEGACY_UNVERIFIED', NULL, 'ACTIVE',
				 'FAILED', 0, '2026-08-02T00:00:00Z', '2026-08-02T02:00:00Z', '2026-08-16T00:00:00Z');
			INSERT INTO found_item_images (
				id, found_item_id, original_filename, stored_filename, legacy_storage_path,
				content_type, size_bytes, is_current, analysis_generation, created_at
			) VALUES (
				401, 302, 'legacy.jpg', 'legacy-302.jpg', 'legacy/items/302.jpg',
				'image/jpeg', 123, false, 0, '2026-08-02T02:05:00Z'
			);
			INSERT INTO point_accounts (user_id, balance) VALUES (101, 3);
			INSERT INTO point_ledger (id, user_id, entry_type, amount, idempotency_key, reason) VALUES
				(201, 101, 'DEMO_GRANT', 3, '00000000-0000-0000-0000-000000000021', NULL),
				(202, 101, 'ADMIN_ADJUSTMENT', 1, '00000000-0000-0000-0000-000000000022', 'legacy'),
				(203, 101, 'CANDIDATE_ACCESS_DEBIT', -1, '00000000-0000-0000-0000-000000000023', NULL);
			""");
	}

	private static void insertV27ManagerFixture() {
		jdbc.update("""
			INSERT INTO users (id, email, password_hash, display_name, status, role, created_at, updated_at)
			VALUES (102, 'manager@example.test', 'hash', 'Manager', 'ACTIVE', 'CENTER_MANAGER', NOW(), NOW())
			""");
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
