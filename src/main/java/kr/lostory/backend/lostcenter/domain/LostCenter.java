package kr.lostory.backend.lostcenter.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Point;

@Getter
@Entity
@Table(name = "lost_centers")
public class LostCenter {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "source_key", unique = true)
	private String sourceKey;

	@Column(nullable = false)
	private String name;

	@Column(nullable = false)
	private String address;

	@JdbcTypeCode(SqlTypes.GEOGRAPHY)
	@Column(nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(name = "contact_phone", nullable = false)
	private String contactPhone;

	@Column(name = "operating_hours", nullable = false)
	private String operatingHours;

	@Column(name = "is_active", nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected LostCenter() {
	}

	public LostCenter(
			String sourceKey,
			String name,
			String address,
			Point location,
			String contactPhone,
			String operatingHours
	) {
		this.sourceKey = sourceKey;
		this.name = name;
		this.address = address;
		this.location = location;
		this.contactPhone = contactPhone;
		this.operatingHours = operatingHours;
		this.active = true;
		this.createdAt = Instant.now();
		this.updatedAt = this.createdAt;
	}

	@PreUpdate
	void updateTimestamp() {
		updatedAt = Instant.now();
	}
}
