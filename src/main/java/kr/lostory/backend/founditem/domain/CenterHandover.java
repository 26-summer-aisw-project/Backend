package kr.lostory.backend.founditem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "center_handovers")
public class CenterHandover {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "found_item_id", nullable = false)
    private Long foundItemId;

    @Column(name = "center_id", nullable = false)
    private Long centerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CenterHandoverStatus status;

    @Column(name = "user_confirmed_at", nullable = false)
    private Instant userConfirmedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    protected CenterHandover() {
    }

    public CenterHandover(Long foundItemId, Long centerId, Instant userConfirmedAt) {
        this.foundItemId = foundItemId;
        this.centerId = centerId;
        this.status = CenterHandoverStatus.USER_CONFIRMED;
        this.userConfirmedAt = userConfirmedAt;
        this.createdAt = userConfirmedAt;
    }

    public void accept(Long managerId, Instant now) {
        status = CenterHandoverStatus.CENTER_CONFIRMED;
        decidedBy = managerId;
        decidedAt = now;
    }

    public void reject(Long managerId, String reason, Instant now) {
        status = CenterHandoverStatus.REJECTED;
        decidedBy = managerId;
        decidedAt = now;
        rejectionReason = reason;
    }

    public void supersede(Instant now) {
        supersededAt = now;
    }
}
