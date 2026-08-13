package kr.lostory.backend.point.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "point_accounts")
public class PointAccount {

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(nullable = false)
	private int balance;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PointAccount() {
	}

	public PointAccount(Long userId) {
		this.userId = userId;
		this.balance = 0;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void updateTimestamp() {
		updatedAt = Instant.now();
	}
}
