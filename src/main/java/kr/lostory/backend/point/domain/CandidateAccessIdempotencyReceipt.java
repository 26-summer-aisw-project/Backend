package kr.lostory.backend.point.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "candidate_access_idempotency_receipts")
public class CandidateAccessIdempotencyReceipt {

	@Id
	@Column(name = "idempotency_key")
	private UUID idempotencyKey;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "report_id", nullable = false)
	private Long reportId;

	@Column(name = "candidate_access_id", nullable = false)
	private Long candidateAccessId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected CandidateAccessIdempotencyReceipt() {
	}

	public CandidateAccessIdempotencyReceipt(
		UUID idempotencyKey,
		Long userId,
		Long reportId,
		Long candidateAccessId
	) {
		this.idempotencyKey = idempotencyKey;
		this.userId = userId;
		this.reportId = reportId;
		this.candidateAccessId = candidateAccessId;
		this.createdAt = Instant.now();
	}
}
