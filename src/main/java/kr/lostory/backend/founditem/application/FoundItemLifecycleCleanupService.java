package kr.lostory.backend.founditem.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import kr.lostory.backend.config.FoundItemProperties;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoundItemLifecycleCleanupService {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final FoundItemProperties properties;

    public FoundItemLifecycleCleanupService(JdbcTemplate jdbc, Clock clock, FoundItemProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${found-item.cleanup-interval:PT1H}",
            initialDelayString = "${found-item.cleanup-initial-delay:PT1M}"
    )
    @Transactional
    public void scheduledCleanup() {
        cleanupAt(clock.instant());
    }

    @Transactional
    public CleanupResult runCleanup() {
        return cleanupAt(clock.instant());
    }

    @Transactional(noRollbackFor = LostoryException.class)
    public void admit(Long itemId) {
        List<LifecycleRow> rows = jdbc.query("""
                SELECT id, status, draft_expires_at, expired_at
                FROM found_items WHERE id = ? FOR UPDATE
                """, (result, row) -> new LifecycleRow(
                        result.getLong("id"), result.getString("status"),
                        result.getTimestamp("draft_expires_at"), result.getTimestamp("expired_at")), itemId);
        if (rows.isEmpty()) {
            return;
        }
        Instant now = databaseNow();
        LifecycleRow item = rows.getFirst();
        if (!item.isDue(now)) {
            return;
        }
        remediate(item, now);
        throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Transactional
    public void remediateDueForFinder(Long finderId) {
        Instant now = databaseNow();
        Timestamp boundary = Timestamp.from(now);
        List<LifecycleRow> rows = jdbc.query("""
                SELECT id, status, draft_expires_at, expired_at
                FROM found_items
                WHERE finder_id = ? AND (
                    (status = 'DRAFT' AND draft_expires_at <= ?)
                    OR (status IN ('ACTIVE', 'PENDING_HANDOVER') AND expired_at <= ?)
                )
                ORDER BY id FOR UPDATE
                """, (result, row) -> new LifecycleRow(
                        result.getLong("id"), result.getString("status"),
                        result.getTimestamp("draft_expires_at"), result.getTimestamp("expired_at")),
                finderId, boundary, boundary);
        rows.forEach(item -> remediate(item, now));
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private void remediate(LifecycleRow item, Instant now) {
        Timestamp boundary = Timestamp.from(now);
        if (item.status().equals("DRAFT")) {
            enqueueImages(item.id(), "DRAFT_EXPIRED", now);
            jdbc.update("DELETE FROM match_candidates WHERE item_id = ?", item.id());
            jdbc.update("DELETE FROM found_items WHERE id = ?", item.id());
            return;
        }
        int expired = jdbc.update("""
                UPDATE found_items SET status = 'EXPIRED', updated_at = ?
                WHERE id = ? AND status IN ('ACTIVE', 'PENDING_HANDOVER')
                """, boundary, item.id());
        if (expired > 0) {
            jdbc.update("""
                    UPDATE lost_reports SET candidates_stale = true, updated_at = ?
                    WHERE status = 'OPEN' AND expired_at > ? AND candidates_stale = false
                    """, boundary, boundary);
        }
    }

    private CleanupResult cleanupAt(Instant now) {
        Timestamp boundary = Timestamp.from(now);
        List<Long> draftIds = jdbc.queryForList("""
                SELECT id FROM found_items
                WHERE status = 'DRAFT' AND draft_expires_at <= ?
                ORDER BY id FOR UPDATE
                """, Long.class, boundary);
        for (Long itemId : draftIds) {
            enqueueImages(itemId, "DRAFT_EXPIRED", now);
            jdbc.update("DELETE FROM match_candidates WHERE item_id = ?", itemId);
            jdbc.update("DELETE FROM found_items WHERE id = ?", itemId);
        }

        List<Long> expiredIds = jdbc.queryForList("""
                UPDATE found_items
                SET status = 'EXPIRED', updated_at = ?
                WHERE status IN ('ACTIVE', 'PENDING_HANDOVER') AND expired_at <= ?
                RETURNING id
                """, Long.class, boundary, boundary);
        if (!expiredIds.isEmpty()) {
            jdbc.update("""
                    UPDATE lost_reports SET candidates_stale = true, updated_at = ?
                    WHERE status = 'OPEN' AND expired_at > ? AND candidates_stale = false
                    """, boundary, boundary);
        }

        int queuedMedia = jdbc.update("""
                INSERT INTO object_deletion_outbox
                    (object_key, idempotency_key, reason, status, attempt_count, next_attempt_at,
                     created_at, updated_at)
                SELECT image.object_key, 'found-item-image:' || image.id, 'TERMINAL_RETENTION',
                       'PENDING', 0, clock_timestamp(), ?, ?
                FROM found_item_images image
                JOIN found_items item ON item.id = image.found_item_id
                WHERE image.object_key IS NOT NULL AND image.object_deleted_at IS NULL
                  AND item.status IN ('EXPIRED', 'RETURNED')
                  AND CASE WHEN item.status = 'EXPIRED' THEN item.expired_at ELSE item.updated_at END <= ?
                ON CONFLICT (idempotency_key) DO NOTHING
                """, boundary, boundary,
                Timestamp.from(now.minus(properties.terminalMediaRetention())));
        return new CleanupResult(draftIds.size(), expiredIds.size(), queuedMedia);
    }

    private void enqueueImages(Long itemId, String reason, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO object_deletion_outbox
                    (object_key, idempotency_key, reason, status, attempt_count, next_attempt_at,
                     created_at, updated_at)
                SELECT object_key, 'found-item-image:' || id, ?, 'PENDING', 0,
                       clock_timestamp(), ?, ?
                FROM found_item_images
                WHERE found_item_id = ? AND object_key IS NOT NULL AND object_deleted_at IS NULL
                ON CONFLICT (idempotency_key) DO NOTHING
                """, reason, timestamp, timestamp, itemId);
    }

    public record CleanupResult(int deletedDrafts, int expiredItems, int queuedMedia) {
    }

    private record LifecycleRow(Long id, String status, Timestamp draftExpiresAt, Timestamp expiredAt) {
        private boolean isDue(Instant now) {
            Timestamp expiresAt = status.equals("DRAFT")
                    ? draftExpiresAt
                    : status.equals("ACTIVE") || status.equals("PENDING_HANDOVER") ? expiredAt : null;
            return expiresAt != null && !expiresAt.toInstant().isAfter(now);
        }
    }
}
