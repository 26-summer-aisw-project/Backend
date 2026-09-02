package kr.lostory.backend.point.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "point_ledger")
public class PointLedger {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false)
	private PointEntryType entryType;

	@Column(nullable = false)
	private int amount;

	@Column(name = "idempotency_key", nullable = false, unique = true)
	private UUID idempotencyKey;

	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(name = "reference_type")
	private PointReferenceType referenceType;

	@Column(name = "reference_id")
	private Long referenceId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected PointLedger() {
	}

	public PointLedger(Long userId, PointEntryType entryType, int amount, UUID idempotencyKey, String reason) {
		this.userId = userId;
		this.entryType = entryType;
		this.amount = amount;
		this.idempotencyKey = idempotencyKey;
		this.reason = reason;
		this.createdAt = Instant.now();
	}

	public static PointLedger signupGrant(Long userId, UUID idempotencyKey, int amount) {
		return new PointLedger(userId, PointEntryType.SIGNUP_GRANT, amount, idempotencyKey, null);
	}

	public static PointLedger candidateAccessDebit(Long userId, Long reportId, UUID idempotencyKey, int cost) {
		PointLedger ledger = new PointLedger(
			userId,
			PointEntryType.CANDIDATE_ACCESS_DEBIT,
			-cost,
			idempotencyKey,
			null
		);
		ledger.referenceType = PointReferenceType.LOST_REPORT;
		ledger.referenceId = reportId;
		return ledger;
	}

	public static PointLedger centerReturnReward(Long userId, Long returnId, UUID idempotencyKey, int amount) {
		PointLedger ledger = new PointLedger(
			userId,
			PointEntryType.CENTER_RETURN_REWARD,
			amount,
			idempotencyKey,
			null
		);
		ledger.referenceType = PointReferenceType.FOUND_ITEM_RETURN;
		ledger.referenceId = returnId;
		return ledger;
	}
}
