package kr.lostory.backend.lostreport.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "lost_reports")
public class LostReport {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "reporter_id", nullable = false)
	private Long reporterId;

	@Column(nullable = false, length = 64)
	private String category;

	@Column(name = "lost_at_from", nullable = false)
	private Instant lostAtFrom;

	@Column(name = "lost_at_to", nullable = false)
	private Instant lostAtTo;

	@Column(nullable = false, length = 1000)
	private String description;

	@Column(name = "search_radius", nullable = false)
	private int searchRadius;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private LostReportStatus status;

	@Column(name = "expired_at", nullable = false)
	private Instant expiredAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected LostReport() {
	}

	public LostReport(
			Long reporterId,
			String category,
			Instant lostAtFrom,
			Instant lostAtTo,
			String description,
			int searchRadius,
			Instant expiredAt
	) {
		this.reporterId = reporterId;
		this.category = category;
		this.lostAtFrom = lostAtFrom;
		this.lostAtTo = lostAtTo;
		this.description = description;
		this.searchRadius = searchRadius;
		this.status = LostReportStatus.OPEN;
		this.expiredAt = expiredAt;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void updateTimestamp() {
		updatedAt = Instant.now();
	}
}
