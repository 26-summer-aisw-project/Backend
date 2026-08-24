package kr.lostory.backend.founditem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "object_deletion_outbox")
public class ObjectDeletionOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String reason;

    @Column(nullable = false)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ObjectDeletionOutbox() {
    }

    public ObjectDeletionOutbox(String objectKey, String idempotencyKey, String reason) {
        this.objectKey = objectKey;
        this.idempotencyKey = idempotencyKey;
        this.reason = reason;
        this.status = "PENDING";
        this.nextAttemptAt = Instant.now();
        this.createdAt = this.nextAttemptAt;
        this.updatedAt = this.nextAttemptAt;
    }

    public void complete(Instant completedAt) {
        status = "DONE";
        updatedAt = completedAt;
        this.completedAt = completedAt;
    }
}
