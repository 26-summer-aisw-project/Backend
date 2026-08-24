package kr.lostory.backend.founditem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "found_item_vision_jobs")
public class FoundItemVisionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "found_item_id", nullable = false)
    private Long foundItemId;

    @Column(name = "image_id", nullable = false)
    private Long imageId;

    @Column(name = "analysis_generation", nullable = false)
    private int analysisGeneration;

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

    protected FoundItemVisionJob() {
    }

    public FoundItemVisionJob(Long foundItemId, Long imageId, int analysisGeneration) {
        this.foundItemId = foundItemId;
        this.imageId = imageId;
        this.analysisGeneration = analysisGeneration;
        this.status = "PENDING";
        this.nextAttemptAt = Instant.now();
        this.createdAt = this.nextAttemptAt;
        this.updatedAt = this.nextAttemptAt;
    }
}
