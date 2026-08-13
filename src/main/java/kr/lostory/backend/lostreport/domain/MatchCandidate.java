package kr.lostory.backend.lostreport.domain;

import java.math.BigDecimal;
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
@Table(name = "match_candidates")
public class MatchCandidate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "report_id", nullable = false)
	private Long reportId;

	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Column(nullable = false)
	private short rank;

	@Column(nullable = false, precision = 5, scale = 2)
	private BigDecimal score;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "score_breakdown", nullable = false, columnDefinition = "jsonb")
	private String scoreBreakdown;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected MatchCandidate() {
	}

	public MatchCandidate(Long reportId, Long itemId, short rank, BigDecimal score, String scoreBreakdown) {
		this.reportId = reportId;
		this.itemId = itemId;
		this.rank = rank;
		this.score = score;
		this.scoreBreakdown = scoreBreakdown;
		this.createdAt = Instant.now();
	}
}
