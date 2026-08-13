package kr.lostory.backend.audit.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Entity
@Table(name = "audit_logs")
public class AuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;

	@Column(nullable = false)
	private String action;

	@Column(name = "target_type")
	private String targetType;

	@Column(name = "target_id")
	private Long targetId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
	private String metadataJson;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	protected AuditLog() {
	}

	public AuditLog(Long userId, String action, String targetType, Long targetId, String metadataJson) {
		this.userId = userId;
		this.action = action;
		this.targetType = targetType;
		this.targetId = targetId;
		this.metadataJson = metadataJson;
		this.occurredAt = Instant.now();
	}
}
