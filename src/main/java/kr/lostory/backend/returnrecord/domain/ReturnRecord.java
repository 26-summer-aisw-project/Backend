package kr.lostory.backend.returnrecord.domain;

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
@Table(name = "return_records")
public class ReturnRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "handover_id", nullable = false, unique = true)
    private Long handoverId;

    @Column(name = "found_item_id", nullable = false, unique = true)
    private Long foundItemId;

    @Column(name = "lost_report_id", nullable = false, unique = true)
    private Long lostReportId;

    @Column(name = "finder_id", nullable = false)
    private Long finderId;

    @Column(name = "center_id", nullable = false)
    private Long centerId;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReturnRecord() {
    }

    public ReturnRecord(
            Long handoverId,
            Long foundItemId,
            Long lostReportId,
            Long finderId,
            Long centerId,
            Long recordedBy,
            Instant createdAt
    ) {
        this.handoverId = handoverId;
        this.foundItemId = foundItemId;
        this.lostReportId = lostReportId;
        this.finderId = finderId;
        this.centerId = centerId;
        this.recordedBy = recordedBy;
        this.status = "RETURNED";
        this.createdAt = createdAt;
    }

    public boolean isCanonical(Long handoverId, Long itemId, Long reportId) {
        return this.handoverId.equals(handoverId)
                && this.foundItemId.equals(itemId)
                && this.lostReportId.equals(reportId);
    }
}
