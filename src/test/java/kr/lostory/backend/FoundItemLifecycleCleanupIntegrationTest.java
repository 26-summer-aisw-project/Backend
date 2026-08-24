package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.common.storage.ObjectStorageException;
import kr.lostory.backend.founditem.application.FoundItemLifecycleCleanupService;
import kr.lostory.backend.founditem.application.ObjectDeletionWorker;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, FoundItemLifecycleCleanupIntegrationTest.BoundaryConfig.class})
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "found-item.terminal-media-retention=P29D"
)
class FoundItemLifecycleCleanupIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired FoundItemLifecycleCleanupService cleanup;
    @Autowired ObjectDeletionWorker deletionWorker;
    @Autowired MutableClock clock;
    @Autowired FakeStorage storage;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM match_candidates");
        jdbc.update("DELETE FROM report_waypoints");
        jdbc.update("DELETE FROM lost_reports");
        jdbc.update("DELETE FROM found_item_vision_jobs");
        jdbc.update("DELETE FROM object_deletion_outbox");
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM found_items");
        storage.reset();
        clock.set(NOW);
    }

    @Test
    void scheduledCleanupDeletesDueDraftMetadataAndRetainsDurableObjectTombstone() throws Exception {
        // Given
        Long ownerId = user().getId();
        Long itemId = draft(ownerId, NOW.minus(Duration.ofHours(24)));
        String key = "found-items/due-draft";
        Long imageId = objectImage(itemId, key, NOW.minus(Duration.ofHours(24)));
        visionJob(itemId, imageId);
        storage.seed(key);

        // When
        cleanup.scheduledCleanup();

        // Then
        assertThat(FoundItemLifecycleCleanupService.class.getMethod("scheduledCleanup")
                .isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(count("found_items", "id", itemId)).isZero();
        assertThat(count("found_item_images", "found_item_id", itemId)).isZero();
        assertThat(count("found_item_vision_jobs", "found_item_id", itemId)).isZero();
        assertThat(storage.contains(key)).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT object_key, reason, status FROM object_deletion_outbox
                WHERE idempotency_key = ?
                """, "found-item-image:" + imageId))
                .containsEntry("object_key", key)
                .containsEntry("reason", "DRAFT_EXPIRED")
                .containsEntry("status", "PENDING");
    }

    @Test
    void cleanupExpiresActiveAndPendingItemsAndStalesEveryOpenUnexpiredReport() {
        // Given
        Long ownerId = user().getId();
        Long centerId = center();
        Long activeId = item(ownerId, "ACTIVE", "LEFT_IN_PLACE", null, "NONE", NOW);
        Long pendingId = item(ownerId, "PENDING_HANDOVER", "HANDED_TO_CENTER", centerId, "NONE", NOW);
        Long futureId = item(ownerId, "ACTIVE", "LEFT_IN_PLACE", null, "NONE", NOW.plusSeconds(1));
        Long openUnexpired = report(ownerId, "OPEN", NOW.plusSeconds(1), false);
        Long unrelatedOpen = report(ownerId, "OPEN", NOW.plusSeconds(1), false);
        Long openExpired = report(ownerId, "OPEN", NOW, false);
        Long closed = report(ownerId, "CLOSED", NOW.plusSeconds(1), false);
        candidate(openUnexpired, activeId);
        candidate(unrelatedOpen, futureId);
        candidate(openExpired, activeId);
        candidate(closed, pendingId);

        // When
        FoundItemLifecycleCleanupService.CleanupResult result = cleanup.runCleanup();

        // Then
        assertThat(result.expiredItems()).isEqualTo(2);
        assertThat(statuses("found_items", activeId, pendingId)).containsExactly("EXPIRED", "EXPIRED");
        assertThat(stale(openUnexpired)).isTrue();
        assertThat(stale(unrelatedOpen)).isTrue();
        assertThat(stale(openExpired)).isFalse();
        assertThat(stale(closed)).isFalse();
    }

    @Test
    void configuredTerminalMediaRetentionKeepsTwentyEightDaysAndDeletesAtTwentyNine() {
        // Given
        Long ownerId = user().getId();
        Long retainedItem = item(ownerId, "EXPIRED", "LEFT_IN_PLACE", null, "NONE",
                NOW.minus(Duration.ofDays(28)));
        Long dueItem = item(ownerId, "EXPIRED", "LEFT_IN_PLACE", null, "NONE",
                NOW.minus(Duration.ofDays(29)));
        String retainedKey = "found-items/retained-28d";
        String dueKey = "found-items/due-29d";
        Long retainedImage = objectImage(retainedItem, retainedKey, NOW.minus(Duration.ofDays(40)));
        Long dueImage = objectImage(dueItem, dueKey, NOW.minus(Duration.ofDays(40)));
        Long legacyImage = legacyImage(dueItem);
        storage.seed(retainedKey);
        storage.seed(dueKey);

        // When
        FoundItemLifecycleCleanupService.CleanupResult result = cleanup.runCleanup();
        boolean processed = deletionWorker.processNext();

        // Then
        assertThat(result.queuedMedia()).isOne();
        assertThat(processed).isTrue();
        assertThat(storage.contains(retainedKey)).isTrue();
        assertThat(storage.contains(dueKey)).isFalse();
        assertThat(imageDeletedAt(retainedImage)).isNull();
        assertThat(imageDeletedAt(dueImage)).isEqualTo(NOW);
        assertThat(count("found_item_images", "id", legacyImage)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM object_deletion_outbox",
                Integer.class)).isOne();
    }

    @Test
    void deletionFailureStaysRetryableAndAlreadyMissingObjectCompletesIdempotently() throws Exception {
        // Given
        Long ownerId = user().getId();
        Long itemId = item(ownerId, "EXPIRED", "LEFT_IN_PLACE", null, "NONE",
                NOW.minus(Duration.ofDays(29)));
        String key = "found-items/retry";
        Long imageId = objectImage(itemId, key, NOW.minus(Duration.ofDays(40)));
        storage.seed(key);
        cleanup.runCleanup();
        storage.failNextDelete();

        // When
        deletionWorker.scheduledProcess();

        // Then
        assertThat(ObjectDeletionWorker.class.getMethod("scheduledProcess")
                .isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(outboxState(key)).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
        assertThat(imageDeletedAt(imageId)).isNull();
        assertThat(storage.contains(key)).isTrue();

        // When
        clock.set(NOW.plus(Duration.ofMinutes(1)));
        jdbc.update("""
                UPDATE object_deletion_outbox SET next_attempt_at = clock_timestamp()
                WHERE object_key = ?
                """, key);
        assertThat(deletionWorker.processNext()).isTrue();
        enqueueMissing("found-items/already-missing", clock.instant());
        assertThat(deletionWorker.processNext()).isTrue();

        // Then
        assertThat(outboxState(key)).containsEntry("status", "DONE").containsEntry("attempt_count", 2);
        assertThat(imageDeletedAt(imageId)).isEqualTo(clock.instant());
        assertThat(outboxState("found-items/already-missing")).containsEntry("status", "DONE");
    }

    @Test
    void outboxEligibilityAndLeaseRecoveryUseDatabaseTimeDespiteWorkerClockSkew() {
        // Given: JVM time is far ahead, but PostgreSQL says this work is not due.
        clock.set(NOW.plus(Duration.ofDays(3650)));
        enqueueRelativeToDatabase("found-items/db-future", "PENDING", "10 minutes");

        // Then
        assertThat(deletionWorker.processNext()).isFalse();
        assertThat(outboxState("found-items/db-future"))
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0);

        // Given: JVM time is far behind, but PostgreSQL says pending work and a lease are due.
        clock.set(NOW.minus(Duration.ofDays(3650)));
        enqueueRelativeToDatabase("found-items/db-due", "PENDING", "-1 second");
        enqueueRelativeToDatabase("found-items/db-expired-lease", "PROCESSING", "-1 second");

        // Then
        assertThat(deletionWorker.processNext()).isTrue();
        assertThat(deletionWorker.processNext()).isTrue();
        assertThat(outboxState("found-items/db-due"))
                .containsEntry("status", "DONE")
                .containsEntry("attempt_count", 1);
        assertThat(outboxState("found-items/db-expired-lease"))
                .containsEntry("status", "DONE")
                .containsEntry("attempt_count", 2);
    }

    private User user() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@task9.example", "hash"));
    }

    private Long draft(Long ownerId, Instant draftExpiresAt) {
        return jdbc.queryForObject("""
                INSERT INTO found_items
                    (finder_id, status, vision_status, handover_status, analysis_generation,
                     created_at, updated_at, draft_expires_at)
                VALUES (?, 'DRAFT', 'PENDING', 'NONE', 1, ?, ?, ?)
                RETURNING id
                """, Long.class, ownerId, ts(draftExpiresAt.minusSeconds(1)),
                ts(draftExpiresAt.minusSeconds(1)), ts(draftExpiresAt));
    }

    private Long item(
            Long ownerId,
            String status,
            String storageMethod,
            Long centerId,
            String handoverStatus,
            Instant expiredAt
    ) {
        return jdbc.queryForObject("""
                INSERT INTO found_items
                    (finder_id, name, category, description, found_at, found_location,
                     storage_method, center_id, handover_status, status, vision_status,
                     analysis_generation, created_at, updated_at, expired_at)
                VALUES (?, 'wallet', 'WALLET', 'black wallet', ?,
                        ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
                        ?, ?, ?, ?, 'READY', 1, ?, ?, ?)
                RETURNING id
                """, Long.class, ownerId, ts(NOW.minus(Duration.ofDays(40))), storageMethod,
                centerId, handoverStatus, status, ts(NOW.minus(Duration.ofDays(40))),
                ts(expiredAt.minusSeconds(1)), ts(expiredAt));
    }

    private Long center() {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, 'center', 'Seoul',
                        ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
                        '02-0000-0000', '09-18', 'official_verified', true, true, ?, ?)
                RETURNING id
                """, Long.class, "task9-" + UUID.randomUUID(), ts(NOW), ts(NOW));
    }

    private Long objectImage(Long itemId, String key, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO found_item_images
                    (found_item_id, original_filename, object_key, is_current, analysis_generation,
                     upload_operation_id, content_type, size_bytes, created_at)
                VALUES (?, 'wallet.png', ?, true, 1, ?, 'image/png', 9, ?)
                RETURNING id
                """, Long.class, itemId, key, UUID.randomUUID(), ts(createdAt));
    }

    private Long legacyImage(Long itemId) {
        return jdbc.queryForObject("""
                INSERT INTO found_item_images
                    (found_item_id, original_filename, stored_filename, legacy_storage_path,
                     is_current, analysis_generation, content_type, size_bytes, created_at)
                VALUES (?, 'legacy.png', 'legacy.png', '/legacy/legacy.png', false, 0,
                        'image/png', 9, ?)
                RETURNING id
                """, Long.class, itemId, ts(NOW.minus(Duration.ofDays(40))));
    }

    private void visionJob(Long itemId, Long imageId) {
        jdbc.update("""
                INSERT INTO found_item_vision_jobs
                    (found_item_id, image_id, analysis_generation, status, attempt_count,
                     next_attempt_at, created_at, updated_at)
                VALUES (?, ?, 1, 'PENDING', 0, ?, ?, ?)
                """, itemId, imageId, ts(NOW), ts(NOW), ts(NOW));
    }

    private Long report(Long ownerId, String status, Instant expiredAt, boolean stale) {
        return jdbc.queryForObject("""
                INSERT INTO lost_reports
                    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
                     effective_search_radius_meters, radius_policy_version, center_guidance,
                     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
                VALUES (?, 'WALLET', ?, ?, 'wallet', 1000, 1000, 'p0-radius-v1', '[]', ?,
                        'p0-matching-v1', ?, ?, ?, ?)
                RETURNING id
                """, Long.class, ownerId, ts(NOW.minusSeconds(2)), ts(NOW.minusSeconds(1)), stale,
                status, ts(expiredAt), ts(NOW.minus(Duration.ofDays(1))), ts(NOW.minusSeconds(1)));
    }

    private void candidate(Long reportId, Long itemId) {
        jdbc.update("""
                INSERT INTO match_candidates
                    (report_id, item_id, rank, score, score_breakdown, created_at)
                VALUES (?, ?, 1, 90, '{}', ?)
                """, reportId, itemId, ts(NOW));
    }

    private void enqueueMissing(String key, Instant now) {
        jdbc.update("""
                INSERT INTO object_deletion_outbox
                    (object_key, idempotency_key, reason, status, attempt_count,
                     next_attempt_at, created_at, updated_at)
                VALUES (?, ?, 'TEST', 'PENDING', 0, clock_timestamp(), ?, ?)
                """, key, "missing:" + key, ts(now), ts(now));
    }

    private void enqueueRelativeToDatabase(String key, String status, String offset) {
        jdbc.update("""
                INSERT INTO object_deletion_outbox
                    (object_key, idempotency_key, reason, status, attempt_count,
                     next_attempt_at, lease_owner, lease_until, created_at, updated_at)
                VALUES (?, ?, 'TEST', ?, ?, clock_timestamp() + (?::interval),
                        CASE WHEN ? = 'PROCESSING' THEN 'stale-worker' END,
                        CASE WHEN ? = 'PROCESSING' THEN clock_timestamp() + (?::interval) END,
                        clock_timestamp(), clock_timestamp())
                """, key, "db-time:" + key, status, status.equals("PROCESSING") ? 1 : 0,
                offset, status, status, offset);
    }

    private Map<String, Object> outboxState(String key) {
        return jdbc.queryForMap("""
                SELECT status, attempt_count, lease_owner, lease_until, completed_at
                FROM object_deletion_outbox WHERE object_key = ?
                """, key);
    }

    private Instant imageDeletedAt(Long imageId) {
        return jdbc.queryForObject("SELECT object_deleted_at FROM found_item_images WHERE id = ?",
                Instant.class, imageId);
    }

    private int count(String table, String column, Long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, id);
    }

    private List<String> statuses(String table, Long... ids) {
        return jdbc.queryForList("SELECT status FROM " + table + " WHERE id IN (?, ?) ORDER BY id",
                String.class, ids[0], ids[1]);
    }

    private boolean stale(Long reportId) {
        return jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
                Boolean.class, reportId);
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    @TestConfiguration
    static class BoundaryConfig {
        @Bean
        @Primary
        MutableClock task9Clock() {
            return new MutableClock(NOW);
        }

        @Bean
        @Primary
        FakeStorage task9Storage() {
            return new FakeStorage();
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static class FakeStorage implements ObjectStorage {
        private final Map<String, ObjectMetadata> objects = new ConcurrentHashMap<>();
        private final AtomicBoolean failDelete = new AtomicBoolean();

        void seed(String key) {
            objects.put(key, new ObjectMetadata(key, "image/png", 9, UUID.randomUUID(), NOW));
        }

        void failNextDelete() {
            failDelete.set(true);
        }

        boolean contains(String key) {
            return objects.containsKey(key);
        }

        void reset() {
            objects.clear();
            failDelete.set(false);
        }

        @Override
        public void put(String key, byte[] bytes, String contentType, UUID uploadOperationId) {
            objects.put(key, new ObjectMetadata(key, contentType, bytes.length, uploadOperationId, NOW));
        }

        @Override
        public StoredObject get(String key) {
            return new StoredObject(new byte[]{1}, "image/png");
        }

        @Override
        public Optional<ObjectMetadata> head(String key) {
            return Optional.ofNullable(objects.get(key));
        }

        @Override
        public void delete(String key) {
            if (failDelete.compareAndSet(true, false)) throw new ObjectStorageException("forced delete failure");
            objects.remove(key);
        }

        @Override
        public List<ObjectMetadata> list(String prefix) {
            return objects.values().stream().filter(value -> value.key().startsWith(prefix)).toList();
        }
    }
}
