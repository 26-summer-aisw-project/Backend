package kr.lostory.backend.founditem.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import kr.lostory.backend.config.FoundItemProperties;
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
            String placeholders = String.join(", ", Collections.nCopies(expiredIds.size(), "?"));
            List<Object> arguments = new ArrayList<>();
            arguments.add(boundary);
            arguments.add(boundary);
            arguments.addAll(expiredIds);
            jdbc.update("""
                    UPDATE lost_reports SET candidates_stale = true, updated_at = ?
                    WHERE status = 'OPEN' AND expired_at > ? AND candidates_stale = false
                      AND EXISTS (
                          SELECT 1 FROM match_candidates candidate
                          WHERE candidate.report_id = lost_reports.id
                            AND candidate.item_id IN (%s)
                      )
                    """.formatted(placeholders), arguments.toArray());
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
}
