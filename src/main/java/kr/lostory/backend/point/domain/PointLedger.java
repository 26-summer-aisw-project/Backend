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
}
