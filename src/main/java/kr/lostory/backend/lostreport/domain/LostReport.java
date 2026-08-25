package kr.lostory.backend.lostreport.domain;

import java.time.Duration;
import java.time.Instant;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

	@Column(name = "effective_search_radius_meters", nullable = false)
	private int effectiveSearchRadiusMeters;

	@Column(name = "radius_policy_version", nullable = false, length = 64)
	private String radiusPolicyVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "center_guidance", nullable = false, columnDefinition = "jsonb")
	private String centerGuidance;

	@Column(name = "candidates_stale", nullable = false)
	private boolean candidatesStale;

	@Column(name = "last_matched_at")
	private Instant lastMatchedAt;

	@Column(name = "matching_policy_version", nullable = false, length = 64)
	private String matchingPolicyVersion;

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
		this.effectiveSearchRadiusMeters = Math.min(3_000, Math.max(500, searchRadius));
		this.radiusPolicyVersion = "p0-radius-v1";
		this.centerGuidance = "[]";
		this.candidatesStale = true;
		this.matchingPolicyVersion = "p0-matching-v1";
		this.status = LostReportStatus.OPEN;
		this.expiredAt = expiredAt;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	public LostReport(
			Long reporterId,
			String category,
			Instant lostAtFrom,
			Instant lostAtTo,
			String description,
			int effectiveSearchRadiusMeters,
			String radiusPolicyVersion,
			String centerGuidance,
			Instant createdAt,
			Duration ttl
	) {
		this.reporterId = reporterId;
		replaceSnapshotInputs(
				category,
				lostAtFrom,
				lostAtTo,
				description,
				effectiveSearchRadiusMeters,
				radiusPolicyVersion,
				centerGuidance,
				createdAt
		);
		this.candidatesStale = true;
		this.matchingPolicyVersion = "p0-matching-v1";
		this.status = LostReportStatus.OPEN;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
		this.expiredAt = createdAt.plus(ttl);
	}

	public void replaceSnapshotInputs(
			String category,
			Instant lostAtFrom,
			Instant lostAtTo,
			String description,
			int effectiveSearchRadiusMeters,
			String radiusPolicyVersion,
			String centerGuidance,
			Instant updatedAt
	) {
		this.category = category;
		this.lostAtFrom = lostAtFrom;
		this.lostAtTo = lostAtTo;
		this.description = description;
		this.searchRadius = effectiveSearchRadiusMeters;
		this.effectiveSearchRadiusMeters = effectiveSearchRadiusMeters;
		this.radiusPolicyVersion = radiusPolicyVersion;
		this.centerGuidance = centerGuidance;
		this.candidatesStale = true;
		this.updatedAt = updatedAt;
	}

	public void recordMatch(Instant matchedAt, String policyVersion) {
		this.candidatesStale = false;
		this.lastMatchedAt = matchedAt;
		this.matchingPolicyVersion = policyVersion;
		this.updatedAt = matchedAt;
	}

	public void markCandidatesStale(Instant staleAt) {
		this.candidatesStale = true;
		this.updatedAt = staleAt;
	}

	public void close(Instant closedAt) {
		if (status != LostReportStatus.OPEN) {
			throw new LostoryException(ErrorCode.REPORT_NOT_OPEN);
		}
		status = LostReportStatus.CLOSED;
		updatedAt = closedAt;
	}
}
