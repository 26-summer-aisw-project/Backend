package kr.lostory.backend.founditem.domain;

import java.math.BigDecimal;
import java.time.Instant;

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
@Table(name = "item_features")
public class ItemFeature {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "item_id", nullable = false)
	private Long itemId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemFeatureKind kind;

	@Column(name = "feature_value", nullable = false)
	private String featureValue;

	@Column(nullable = false)
	private short ordinal;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemFeatureSource source;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ItemFeatureVisibility visibility;

	@Column(precision = 4, scale = 3)
	private BigDecimal confidence;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ItemFeature() {
	}

	public ItemFeature(
			Long itemId,
			ItemFeatureKind kind,
			String featureValue,
			short ordinal,
			ItemFeatureSource source,
			ItemFeatureVisibility visibility,
			BigDecimal confidence
	) {
		this.itemId = itemId;
		this.kind = kind;
		this.featureValue = featureValue;
		this.ordinal = ordinal;
		this.source = source;
		this.visibility = visibility;
		this.confidence = confidence;
		this.createdAt = Instant.now();
	}
}
