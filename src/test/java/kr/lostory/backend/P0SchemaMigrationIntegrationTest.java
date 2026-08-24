package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class P0SchemaMigrationIntegrationTest {

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
    void preservesTheV19LifecycleFixtureBeforeTheP0Migration() {
        // Given
        cleanAndMigrate("19");

        // When
        insertV19Fixture();

        // Then
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_items", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForList(
                "SELECT id || ',' || storage_method || ',' || status FROM found_items ORDER BY id",
                String.class
        )).containsExactly(
                "301,LEFT_IN_PLACE,ACTIVE",
                "302,LEFT_IN_PLACE,EXPIRED",
                "303,LEFT_IN_PLACE,RETURNED",
                "304,MOVED_TO_SAFE_PLACE,ACTIVE",
                "305,MOVED_TO_SAFE_PLACE,EXPIRED",
                "306,MOVED_TO_SAFE_PLACE,RETURNED",
                "307,HANDED_TO_CENTER,ACTIVE",
                "308,HANDED_TO_CENTER,EXPIRED",
                "309,HANDED_TO_CENTER,RETURNED"
        );
        assertThat(jdbc.queryForObject(
                "SELECT ST_X(found_location::geometry) || ',' || ST_Y(found_location::geometry) "
                        + "FROM found_items WHERE id = 307",
                String.class
        )).isEqualTo("126.957,37.496");
        assertThat(jdbc.queryForObject(
                "SELECT id || ',' || found_item_id || ',' || storage_path FROM found_item_images WHERE id = 401",
                String.class
        )).isEqualTo("401,307,legacy/found-items/307.jpg");
        assertThat(jdbc.queryForObject(
                "SELECT r.id || ',' || w.id || ',' || c.id || ',' || c.item_id "
                        + "FROM lost_reports r JOIN report_waypoints w ON w.report_id = r.id "
                        + "JOIN match_candidates c ON c.report_id = r.id WHERE r.id = 501",
                String.class
        )).isEqualTo("501,601,701,301");
        System.out.println("P0_SCHEMA_BASELINE_OBSERVABLE version=19 items=9 image=401 report=501 waypoint=601 candidate=701");
    }

    @Test
    void migratesAnEmptyDatabaseToTheP0Schema() {
        // Given
        cleanAndMigrate("19");

        // When
        migrateLatest();

        // Then
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name IN "
                        + "('found_items', 'found_item_images', 'lost_reports') "
                        + "AND column_name IN ('draft_expires_at', 'object_key', 'effective_search_radius_meters')",
                Integer.class
        )).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.found_item_vision_jobs')", String.class))
                .isEqualTo("found_item_vision_jobs");
        assertThat(jdbc.queryForObject("SELECT to_regclass('public.object_deletion_outbox')", String.class))
                .isEqualTo("object_deletion_outbox");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'object_deletion_outbox' "
                        + "AND column_name IN ('reason', 'last_error_code')",
                Integer.class
        )).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' "
                        + "AND table_name = 'found_items' AND column_name = 'legacy_handover_place_name'",
                Integer.class
        )).isOne();
    }

    @Test
    void acceptsEveryP0LifecycleStorageCombination() {
        // Given
        migrateFixtureToLatest();

        // When
        jdbc.execute("""
                INSERT INTO found_items (
                    id, finder_id, status, vision_status, handover_status, analysis_generation,
                    created_at, updated_at, draft_expires_at
                ) VALUES (
                    310, 101, 'DRAFT', 'PENDING', 'NONE', 1,
                    '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z', '2026-08-05T00:00:00Z'
                );
                INSERT INTO found_items (
                    id, finder_id, name, category, description, found_at, storage_method,
                    storage_description, center_id, handover_status, handed_at, status,
                    vision_status, analysis_generation, created_at, updated_at, expired_at
                ) VALUES
                    (311, 101, 'pending', 'WALLET', 'pending handover', '2026-08-04T00:00:00Z',
                     'HANDED_TO_CENTER', NULL, 201, 'NONE', NULL, 'PENDING_HANDOVER', 'READY', 1,
                     '2026-08-04T00:00:00Z', '2026-08-04T01:00:00Z', '2026-08-18T01:00:00Z'),
                    (312, 101, 'left', 'WALLET', 'left in place', '2026-08-04T00:00:00Z',
                     'LEFT_IN_PLACE', NULL, NULL, 'NONE', NULL, 'ACTIVE', 'READY', 1,
                     '2026-08-04T00:00:00Z', '2026-08-04T01:00:00Z', '2026-08-18T01:00:00Z'),
                    (313, 101, 'moved', 'WALLET', 'moved safely', '2026-08-04T00:00:00Z',
                     'MOVED_TO_SAFE_PLACE', 'locker', NULL, 'NONE', NULL, 'ACTIVE', 'READY', 1,
                     '2026-08-04T00:00:00Z', '2026-08-04T01:00:00Z', '2026-08-18T01:00:00Z'),
                    (314, 101, 'handed', 'WALLET', 'confirmed handover', '2026-08-04T00:00:00Z',
                     'HANDED_TO_CENTER', NULL, 201, 'USER_CONFIRMED', '2026-08-04T02:00:00Z',
                     'ACTIVE', 'READY', 1, '2026-08-04T00:00:00Z', '2026-08-04T02:00:00Z',
                     '2026-08-18T02:00:00Z'),
                    (315, 101, 'expired', 'WALLET', 'expired item', '2026-08-04T00:00:00Z',
                     'LEFT_IN_PLACE', NULL, NULL, 'NONE', NULL, 'EXPIRED', 'FAILED', 1,
                     '2026-08-04T00:00:00Z', '2026-08-20T00:00:00Z', '2026-08-18T00:00:00Z'),
                    (316, 101, 'returned', 'WALLET', 'returned item', '2026-08-04T00:00:00Z',
                     'MOVED_TO_SAFE_PLACE', 'locker', NULL, 'NONE', NULL, 'RETURNED', 'FAILED', 1,
                     '2026-08-04T00:00:00Z', '2026-08-20T00:00:00Z', '2026-08-18T00:00:00Z');
                """);

        // Then
        assertThat(jdbc.queryForObject(
                "SELECT name IS NULL AND category IS NULL AND description IS NULL AND found_at IS NULL "
                        + "AND storage_method IS NULL AND expired_at IS NULL AND draft_expires_at IS NOT NULL "
                        + "FROM found_items WHERE id = 310",
                Boolean.class
        )).isTrue();
        assertThat(jdbc.queryForList(
                "SELECT status FROM found_items WHERE id BETWEEN 311 AND 316 ORDER BY id",
                String.class
        )).containsExactly("PENDING_HANDOVER", "ACTIVE", "ACTIVE", "ACTIVE", "EXPIRED", "RETURNED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM found_items WHERE id BETWEEN 311 AND 314 AND expired_at > updated_at",
                Integer.class
        )).isEqualTo(4);
        System.out.println("P0_SCHEMA_STATE_OBSERVABLE draft-nullable=true pending-expiry-anchored=true lifecycle-combinations=accepted");
    }

    @Test
    void acceptsTheNewS3ImageShape() {
        // Given
        migrateFixtureToLatest();

        // When
        jdbc.execute("""
                INSERT INTO found_item_images (
                    id, found_item_id, original_filename, content_type, size_bytes, object_key,
                    is_current, analysis_generation, upload_operation_id, created_at
                ) VALUES (
                    402, 301, 'current.jpg', 'image/jpeg', 200, 'found-items/301/current.jpg',
                    true, 1, '00000000-0000-0000-0000-000000000010', '2026-08-04T00:00:00Z'
                )
                """);

        // Then
        assertThat(jdbc.queryForObject(
                "SELECT legacy_storage_path IS NULL AND object_key = 'found-items/301/current.jpg' "
                        + "AND is_current AND analysis_generation = 1 AND object_deleted_at IS NULL "
                        + "FROM found_item_images WHERE id = 402",
                Boolean.class
        )).isTrue();
        System.out.println("P0_SCHEMA_IMAGE_OBSERVABLE s3-shape=accepted current=true generation=1");
    }

    @Test
    void acceptsEveryVisionJobStateShape() {
        // Given
        migrateFixtureToLatest();

        // When
        jdbc.execute("""
                INSERT INTO found_item_vision_jobs (
                    found_item_id, image_id, analysis_generation, status, attempt_count,
                    lease_owner, lease_until, last_error, completed_at
                ) VALUES
                    (307, 401, 1, 'PENDING', 0, NULL, NULL, NULL, NULL),
                    (307, 401, 2, 'PROCESSING', 1, 'worker-1', '2026-08-04T01:00:00Z', NULL, NULL),
                    (307, 401, 3, 'READY', 1, NULL, NULL, NULL, '2026-08-04T01:00:00Z'),
                    (307, 401, 4, 'FAILED', 3, NULL, NULL, 'provider failure', '2026-08-04T01:00:00Z'),
                    (307, 401, 5, 'SUPERSEDED', 0, NULL, NULL, NULL, '2026-08-04T01:00:00Z')
                """);

        // Then
        assertThat(jdbc.queryForList(
                "SELECT status FROM found_item_vision_jobs ORDER BY analysis_generation",
                String.class
        )).containsExactly("PENDING", "PROCESSING", "READY", "FAILED", "SUPERSEDED");
        System.out.println("P0_SCHEMA_VISION_JOB_OBSERVABLE states=PENDING,PROCESSING,READY,FAILED,SUPERSEDED");
    }

    @Test
    void preservesAndSafelyBackfillsTheV19FixtureDuringP0Migration() {
        // Given
        cleanAndMigrate("19");
        insertV19Fixture();

        // When
        migrateLatest();

        // Then
        assertThat(jdbc.queryForList(
                "SELECT id || ',' || storage_method || ',' || status FROM found_items ORDER BY id",
                String.class
        )).containsExactly(
                "301,LEFT_IN_PLACE,ACTIVE", "302,LEFT_IN_PLACE,EXPIRED", "303,LEFT_IN_PLACE,RETURNED",
                "304,MOVED_TO_SAFE_PLACE,ACTIVE", "305,MOVED_TO_SAFE_PLACE,EXPIRED",
                "306,MOVED_TO_SAFE_PLACE,RETURNED", "307,HANDED_TO_CENTER,ACTIVE",
                "308,HANDED_TO_CENTER,EXPIRED", "309,HANDED_TO_CENTER,RETURNED"
        );
        assertThat(jdbc.queryForList(
                "SELECT id || ',' || handover_status || ',' || coalesce(center_id::text, 'null') || ',' "
                        + "|| coalesce(handed_at::text, 'null') || ',' || legacy_handover_place_name "
                        + "FROM found_items WHERE storage_method = 'HANDED_TO_CENTER' ORDER BY id",
                String.class
        )).containsExactly(
                "307,LEGACY_UNVERIFIED,null,null,Legacy handover desk 7",
                "308,LEGACY_UNVERIFIED,null,null,Legacy handover desk 8",
                "309,LEGACY_UNVERIFIED,null,null,Legacy handover desk 9"
        );
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM found_items WHERE vision_status = 'FAILED' "
                        + "AND analysis_generation = 0 AND draft_expires_at IS NULL AND expired_at IS NOT NULL",
                Integer.class
        )).isEqualTo(9);
        assertThat(jdbc.queryForObject(
                "SELECT legacy_storage_path || ',' || coalesce(object_key, 'null') || ',' || is_current "
                        + "FROM found_item_images WHERE id = 401",
                String.class
        )).isEqualTo("legacy/found-items/307.jpg,null,false");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_item_vision_jobs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM item_features WHERE kind = 'PUBLIC_DESCRIPTION'",
                Integer.class
        )).isEqualTo(9);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM item_features WHERE item_id = 301 AND kind = 'PUBLIC_DESCRIPTION'",
                Integer.class
        )).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT effective_search_radius_meters || ',' || radius_policy_version || ',' "
                        + "|| matching_policy_version || ',' "
                        + "|| candidates_stale || ',' || center_guidance::text || ',' "
                        + "|| coalesce(last_matched_at::text, 'null') "
                        + "FROM lost_reports WHERE id = 501",
                String.class
        )).isEqualTo("500,legacy-v19,legacy-unmatched,true,[],null");
        assertThat(jdbc.queryForObject(
                "SELECT r.id || ',' || w.id || ',' || c.id || ',' || c.item_id "
                        + "FROM lost_reports r JOIN report_waypoints w ON w.report_id = r.id "
                        + "JOIN match_candidates c ON c.report_id = r.id WHERE r.id = 501",
                String.class
        )).isEqualTo("501,601,701,301");
        System.out.println("P0_SCHEMA_MIGRATION_OBSERVABLE legacy-items=9 handed=3 image-current=false "
                + "vision-jobs=0 report-stale=true candidate=701");
    }

    @Test
    void rejectsDuplicateCurrentImages() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_item_images (
                    found_item_id, original_filename, content_type, size_bytes, object_key,
                    is_current, analysis_generation, upload_operation_id
                ) VALUES
                    (301, 'new-a.jpg', 'image/jpeg', 100, 'items/301/a.jpg', true, 1,
                     '00000000-0000-0000-0000-000000000001'),
                    (301, 'new-b.jpg', 'image/jpeg', 100, 'items/301/b.jpg', true, 1,
                     '00000000-0000-0000-0000-000000000002')
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE duplicate-current-image=rejected");
    }

    @Test
    void rejectsDuplicateVisionJobsForOneGeneration() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_item_vision_jobs (found_item_id, image_id, analysis_generation, status)
                VALUES (307, 401, 0, 'PENDING'), (307, 401, 0, 'PENDING')
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE duplicate-vision-job=rejected");
    }

    @Test
    void rejectsDuplicateObjectDeletionIdempotencyKeys() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO object_deletion_outbox (object_key, idempotency_key, reason)
                VALUES ('items/301/a.jpg', 'delete:301:a', 'TERMINAL_RETENTION'),
                       ('items/301/b.jpg', 'delete:301:a', 'REPLACED_IMAGE')
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE duplicate-outbox-key=rejected");
    }

    @Test
    void rejectsInvalidDraftExpirationState() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_items (
                    finder_id, status, vision_status, handover_status, analysis_generation,
                    draft_expires_at, expired_at
                ) VALUES (
                    101, 'DRAFT', 'PENDING', 'NONE', 1,
                    '2026-08-05T00:00:00Z', '2026-08-06T00:00:00Z'
                )
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE invalid-draft-expiration=rejected");
    }

    @Test
    void rejectsDraftWithoutDraftExpiration() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_items (
                    finder_id, status, vision_status, handover_status, analysis_generation
                ) VALUES (101, 'DRAFT', 'PENDING', 'NONE', 1)
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE draft-without-expiration=rejected");
    }

    @Test
    void rejectsFinalizedItemWithMissingRequiredField() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_items (
                    finder_id, name, description, found_at, storage_method, status, vision_status,
                    handover_status, analysis_generation, created_at, updated_at, expired_at
                ) VALUES (
                    101, 'missing category', 'invalid finalized item', '2026-08-04T00:00:00Z',
                    'LEFT_IN_PLACE', 'ACTIVE', 'READY', 'NONE', 1, '2026-08-04T00:00:00Z',
                    '2026-08-04T01:00:00Z', '2026-08-18T01:00:00Z'
                )
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE finalized-required-field-null=rejected");
    }

    @Test
    void rejectsPendingHandoverWithoutFutureExpiryAnchor() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_items (
                    finder_id, name, category, description, found_at, storage_method, center_id,
                    status, vision_status, handover_status, analysis_generation,
                    created_at, updated_at, expired_at
                ) VALUES (
                    101, 'pending', 'WALLET', 'bad expiry anchor', '2026-08-04T00:00:00Z',
                    'HANDED_TO_CENTER', 201, 'PENDING_HANDOVER', 'READY', 'NONE', 1,
                    '2026-08-04T00:00:00Z', '2026-08-04T01:00:00Z', '2026-08-04T01:00:00Z'
                )
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE pending-expiry-anchor=rejected");
    }

    @Test
    void rejectsInvalidActiveStorageState() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_items (
                    finder_id, name, category, description, found_at, status, vision_status,
                    handover_status, analysis_generation, created_at, updated_at, expired_at
                ) VALUES (
                    101, 'invalid item', 'WALLET', 'invalid storage state', '2026-08-04T00:00:00Z',
                    'ACTIVE', 'READY', 'NONE', 1, '2026-08-04T00:00:00Z',
                    '2026-08-04T00:00:00Z', '2026-08-18T00:00:00Z'
                )
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE invalid-state-storage=rejected");
    }

    @Test
    void rejectsPendingHandoverWithoutSelectedCenter() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_items (
                    finder_id, name, category, description, found_at, storage_method, status,
                    vision_status, handover_status, analysis_generation, created_at, updated_at, expired_at
                ) VALUES (
                    101, 'pending', 'WALLET', 'missing center', '2026-08-04T00:00:00Z',
                    'HANDED_TO_CENTER', 'PENDING_HANDOVER', 'READY', 'NONE', 1,
                    '2026-08-04T00:00:00Z', '2026-08-04T01:00:00Z', '2026-08-18T01:00:00Z'
                )
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE pending-without-center=rejected");
    }

    @Test
    void rejectsCenterOnNonHandoverStorage() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE found_items SET center_id = 201 WHERE id = 301"
        )).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE non-handover-center=rejected");
    }

    @Test
    void rejectsConfirmedHandoverWithoutServerTimestamp() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_items (
                    finder_id, name, category, description, found_at, storage_method, center_id,
                    status, vision_status, handover_status, analysis_generation,
                    created_at, updated_at, expired_at
                ) VALUES (
                    101, 'confirmed', 'WALLET', 'missing handed at', '2026-08-04T00:00:00Z',
                    'HANDED_TO_CENTER', 201, 'ACTIVE', 'READY', 'USER_CONFIRMED', 1,
                    '2026-08-04T00:00:00Z', '2026-08-04T01:00:00Z', '2026-08-18T01:00:00Z'
                )
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE confirmed-without-handed-at=rejected");
    }

    @Test
    void rejectsImageWithLegacyAndObjectStorageTogether() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_item_images (
                    found_item_id, original_filename, stored_filename, legacy_storage_path,
                    content_type, size_bytes, object_key, is_current, analysis_generation,
                    upload_operation_id
                ) VALUES (
                    301, 'invalid.jpg', 'legacy.jpg', 'legacy/invalid.jpg', 'image/jpeg', 100,
                    'found-items/301/invalid.jpg', true, 1,
                    '00000000-0000-0000-0000-000000000020'
                )
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE mixed-image-storage=rejected");
    }

    @Test
    void rejectsDeletedImageAsCurrent() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_item_images (
                    found_item_id, original_filename, content_type, size_bytes, object_key,
                    is_current, analysis_generation, upload_operation_id, object_deleted_at
                ) VALUES (
                    301, 'deleted.jpg', 'image/jpeg', 100, 'found-items/301/deleted.jpg',
                    true, 1, '00000000-0000-0000-0000-000000000021', '2026-08-04T01:00:00Z'
                )
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE deleted-current-image=rejected");
    }

    @Test
    void rejectsProcessingVisionJobWithoutLease() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_item_vision_jobs (
                    found_item_id, image_id, analysis_generation, status
                ) VALUES (307, 401, 1, 'PROCESSING')
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE processing-job-without-lease=rejected");
    }

    @Test
    void rejectsReadyVisionJobWithoutCompletion() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO found_item_vision_jobs (
                    found_item_id, image_id, analysis_generation, status
                ) VALUES (307, 401, 1, 'READY')
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE ready-job-without-completion=rejected");
    }

    @Test
    void storesObjectDeletionReasonAndRetryErrorCode() {
        // Given
        migrateFixtureToLatest();

        // When
        jdbc.execute("""
                INSERT INTO object_deletion_outbox (
                    object_key, idempotency_key, reason, attempt_count, last_error_code
                ) VALUES ('found-items/301/old.jpg', 'delete:301:old', 'REPLACED_IMAGE', 2, 'S3_TIMEOUT')
                """);

        // Then
        assertThat(jdbc.queryForObject(
                "SELECT reason || ',' || attempt_count || ',' || last_error_code "
                        + "FROM object_deletion_outbox WHERE idempotency_key = 'delete:301:old'",
                String.class
        )).isEqualTo("REPLACED_IMAGE,2,S3_TIMEOUT");
        System.out.println("P0_SCHEMA_OUTBOX_OBSERVABLE reason=REPLACED_IMAGE attempt=2 error=S3_TIMEOUT");
    }

    @Test
    void rejectsProcessingOutboxRowWithoutLease() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.execute("""
                INSERT INTO object_deletion_outbox (object_key, idempotency_key, reason, status)
                VALUES ('found-items/301/old.jpg', 'delete:301:unleased', 'REPLACED_IMAGE', 'PROCESSING')
                """)).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE processing-outbox-without-lease=rejected");
    }

    @Test
    void rejectsFabricatedConfirmationOnALegacyHandover() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE found_items SET handover_status = 'USER_CONFIRMED', center_id = 201, "
                        + "handed_at = '2026-08-04T00:00:00Z', legacy_handover_place_name = NULL WHERE id = 307"
        )).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE fabricated-legacy-confirmation=rejected");
    }

    @Test
    void rejectsDeletingAReferencedCenter() {
        // Given
        migrateFixtureToLatest();
        jdbc.execute("""
                INSERT INTO found_items (
                    id, finder_id, name, category, description, found_at, found_location,
                    storage_method, center_id, handover_status, status, vision_status,
                    analysis_generation, created_at, updated_at, expired_at
                ) VALUES (
                    310, 101, 'new item', 'WALLET', 'new description', '2026-08-04T00:00:00Z',
                    ST_SetSRID(ST_MakePoint(126.957, 37.496), 4326)::geography,
                    'HANDED_TO_CENTER', 201, 'NONE', 'PENDING_HANDOVER', 'READY', 1,
                    '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z', '2026-08-18T00:00:00Z'
                )
                """);

        // When / Then
        assertThatThrownBy(() -> jdbc.update("DELETE FROM lost_centers WHERE id = 201"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE referenced-center-delete=rejected");
    }

    @Test
    void rejectsMutationOfLegacyTerminalHandoverRows() {
        // Given
        migrateFixtureToLatest();

        // When / Then
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE found_items SET description = 'changed' WHERE id = 308"
        )).hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE legacy-terminal-mutation=rejected");
    }

    @Test
    void deletesOrdinaryAndDraftRowsButRejectsLegacyTerminalDelete() {
        // Given
        migrateFixtureToLatest();
        jdbc.execute("""
                INSERT INTO found_items (
                    id, finder_id, status, vision_status, handover_status, analysis_generation,
                    created_at, updated_at, draft_expires_at
                ) VALUES (
                    310, 101, 'DRAFT', 'PENDING', 'NONE', 1,
                    '2026-08-04T00:00:00Z', '2026-08-04T00:00:00Z', '2026-08-05T00:00:00Z'
                )
                """);

        // When / Then
        assertThat(jdbc.update("DELETE FROM found_items WHERE id = 304")).isOne();
        assertThat(jdbc.update("DELETE FROM found_items WHERE id = 310")).isOne();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM found_items WHERE id = 308"))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        System.out.println("P0_SCHEMA_CONSTRAINT_OBSERVABLE ordinary-delete=1 draft-delete=1 "
                + "legacy-terminal-delete=rejected");
    }

    private static void cleanAndMigrate(String target) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .target(target)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    private static void migrateLatest() {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    private static void migrateFixtureToLatest() {
        cleanAndMigrate("19");
        insertV19Fixture();
        migrateLatest();
    }

    private static void insertV19Fixture() {
        jdbc.execute("""
                INSERT INTO users (id, email, password_hash, status, display_name, role, created_at, updated_at)
                VALUES (101, 'migration@example.test', 'hash', 'ACTIVE', 'Migration', 'USER',
                        '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');

                INSERT INTO lost_centers (
                    id, source_key, name, address, location, contact_phone, operating_hours,
                    is_active, verification_status, is_csv_managed, created_at, updated_at
                ) VALUES (
                    201, 'fixture:center', 'Fixture Center', 'Fixture Address',
                    ST_SetSRID(ST_MakePoint(126.958, 37.497), 4326)::geography,
                    '02-000-0000', '09:00-18:00', true, 'official_verified', false,
                    '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z'
                );

                INSERT INTO found_items (
                    id, finder_id, name, category, description, found_at, found_location_detail,
                    found_location, found_address, storage_method, storage_description,
                    handover_place_name, status, created_at, updated_at, expired_at
                )
                SELECT
                    300 + n,
                    101,
                    'legacy item ' || n,
                    'WALLET',
                    'legacy description ' || n,
                    '2026-08-02T01:00:00Z',
                    'Fixture location',
                    ST_SetSRID(ST_MakePoint(126.950 + n * 0.001, 37.489 + n * 0.001), 4326)::geography,
                    'Fixture address',
                    CASE WHEN n <= 3 THEN 'LEFT_IN_PLACE'
                         WHEN n <= 6 THEN 'MOVED_TO_SAFE_PLACE'
                         ELSE 'HANDED_TO_CENTER' END,
                    CASE WHEN n BETWEEN 4 AND 6 THEN 'Fixture locker' ELSE NULL END,
                    CASE WHEN n >= 7 THEN 'Legacy handover desk ' || n ELSE NULL END,
                    CASE n % 3 WHEN 1 THEN 'ACTIVE' WHEN 2 THEN 'EXPIRED' ELSE 'RETURNED' END,
                    '2026-08-02T02:00:00Z',
                    '2026-08-02T02:00:00Z',
                    '2026-08-16T02:00:00Z'
                FROM generate_series(1, 9) AS n;

                INSERT INTO found_item_images (
                    id, found_item_id, original_filename, stored_filename, storage_path,
                    content_type, size_bytes, created_at
                ) VALUES (
                    401, 307, 'legacy.jpg', 'legacy-307.jpg', 'legacy/found-items/307.jpg',
                    'image/jpeg', 12345, '2026-08-02T02:05:00Z'
                );

                INSERT INTO item_features (
                    id, item_id, kind, feature_value, ordinal, source, visibility, confidence, created_at
                ) VALUES (
                    451, 301, 'PUBLIC_DESCRIPTION', 'existing confirmed description', 1,
                    'FINDER', 'CANDIDATE_VIEW', NULL, '2026-08-02T02:10:00Z'
                );

                INSERT INTO lost_reports (
                    id, reporter_id, category, lost_at_from, lost_at_to, description,
                    search_radius, status, expired_at, created_at, updated_at
                ) VALUES (
                    501, 101, 'WALLET', '2026-08-01T00:00:00Z', '2026-08-03T00:00:00Z',
                    'legacy report', 100, 'OPEN', '2026-08-20T00:00:00Z',
                    '2026-08-03T00:00:00Z', '2026-08-03T00:00:00Z'
                );

                INSERT INTO report_waypoints (id, report_id, ordinal, place_name, location, created_at)
                VALUES (601, 501, 1, 'Fixture waypoint',
                        ST_SetSRID(ST_MakePoint(126.957, 37.496), 4326)::geography,
                        '2026-08-03T00:01:00Z');

                INSERT INTO match_candidates (id, report_id, item_id, rank, score, score_breakdown, created_at)
                VALUES (701, 501, 301, 1, 88.50, '{"legacy": true}', '2026-08-03T00:02:00Z');
                """);
    }
}
