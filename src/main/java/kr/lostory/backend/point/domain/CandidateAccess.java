package kr.lostory.backend.point.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "candidate_accesses")
public class CandidateAccess {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "report_id", nullable = false, unique = true)
	private Long reportId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "debit_transaction_id", nullable = false, unique = true)
	private Long debitTransactionId;

	@Column(name = "unlocked_at", nullable = false)
	private Instant unlockedAt;

	protected CandidateAccess() {
	}

	public CandidateAccess(Long reportId, Long userId, Long debitTransactionId) {
		this.reportId = reportId;
		this.userId = userId;
		this.debitTransactionId = debitTransactionId;
		this.unlockedAt = Instant.now();
	}
}
