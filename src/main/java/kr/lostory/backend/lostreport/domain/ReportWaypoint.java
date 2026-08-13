package kr.lostory.backend.lostreport.domain;

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
import org.locationtech.jts.geom.Point;

@Getter
@Entity
@Table(name = "report_waypoints")
public class ReportWaypoint {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "report_id", nullable = false)
	private Long reportId;

	@Column(nullable = false)
	private short ordinal;

	@Column(name = "place_name")
	private String placeName;

	@JdbcTypeCode(SqlTypes.GEOGRAPHY)
	@Column(nullable = false, columnDefinition = "geography(Point, 4326)")
	private Point location;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	protected ReportWaypoint() {
	}

	public ReportWaypoint(Long reportId, short ordinal, String placeName, Point location) {
		this.reportId = reportId;
		this.ordinal = ordinal;
		this.placeName = placeName;
		this.location = location;
		this.createdAt = Instant.now();
	}
}
