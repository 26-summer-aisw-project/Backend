package kr.lostory.backend.founditem.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import kr.lostory.backend.common.storage.ObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ObjectDeletionWorker {

    private final ObjectStorage storage;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ObjectDeletionWorker(
            ObjectStorage storage,
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.storage = storage;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${object-storage.deletion-worker-interval:PT5S}",
            initialDelayString = "${object-storage.deletion-worker-initial-delay:PT30S}"
    )
    public void scheduledProcess() {
        processNext();
    }

    public boolean processNext() {
        Claim claim = transactions.execute(ignored -> claim());
        if (claim == null) {
            return false;
        }
        try {
            if (storage.head(claim.objectKey()).isPresent()) {
                storage.delete(claim.objectKey());
            }
            transactions.executeWithoutResult(ignored -> complete(claim, clock.instant()));
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(ignored -> retry(claim, exception, clock.instant()));
        }
        return true;
    }

    private Claim claim() {
        String leaseOwner = UUID.randomUUID().toString();
        return jdbc.query("""
                WITH next AS (
                    SELECT id FROM object_deletion_outbox
                    WHERE (status = 'PENDING' AND next_attempt_at <= clock_timestamp())
                       OR (status = 'PROCESSING' AND lease_until <= clock_timestamp())
                    ORDER BY id FOR UPDATE SKIP LOCKED LIMIT 1
                )
                UPDATE object_deletion_outbox work
                SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                    lease_owner = ?, lease_until = clock_timestamp() + INTERVAL '1 minute',
                    updated_at = clock_timestamp(),
                    last_error_code = NULL, last_error = NULL
                FROM next WHERE work.id = next.id
                RETURNING work.id, work.object_key
                """, (resultSet, rowNumber) -> new Claim(
                        resultSet.getLong("id"), resultSet.getString("object_key"), leaseOwner),
                leaseOwner)
                .stream().findFirst().orElse(null);
    }

    private void complete(Claim claim, Instant completedAt) {
        int completed = jdbc.update("""
                UPDATE object_deletion_outbox
                SET status = 'DONE', lease_owner = NULL, lease_until = NULL,
                    updated_at = ?, completed_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, Timestamp.from(completedAt), Timestamp.from(completedAt), claim.id(), claim.leaseOwner());
        if (completed == 1) {
            jdbc.update("""
                    UPDATE found_item_images
                    SET is_current = false, object_deleted_at = ?
                    WHERE object_key = ? AND object_deleted_at IS NULL
                    """, Timestamp.from(completedAt), claim.objectKey());
        }
    }

    private void retry(Claim claim, RuntimeException exception, Instant failedAt) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        jdbc.update("""
                UPDATE object_deletion_outbox
                SET status = 'PENDING', lease_owner = NULL, lease_until = NULL,
                    next_attempt_at = clock_timestamp() + INTERVAL '1 minute',
                    updated_at = ?, last_error_code = ?, last_error = ?
                WHERE id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, Timestamp.from(failedAt),
                exception.getClass().getSimpleName(), message.substring(0, Math.min(message.length(), 2000)),
                claim.id(), claim.leaseOwner());
    }

    private record Claim(Long id, String objectKey, String leaseOwner) {
    }
}
