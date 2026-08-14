package kr.lostory.backend.lostcenter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
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

    @Column(name = "center_key", nullable = false, length = 100)
    private String centerKey;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "parent_place", length = 100)
    private String parentPlace;

    @Column(name = "phone_number", length = 100)
    private String phoneNumber;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(name = "detail_location", length = 255)
    private String detailLocation;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(nullable = false, columnDefinition = "geography(Point, 4326)")
    private Point location;

    @Column(name = "operating_hours", length = 255)
    private String operatingHours;

    @Column(name = "handoff_available", nullable = false, length = 20)
    private String handoffAvailable;

    @Column(name = "verification_status", nullable = false, length = 80)
    private String verificationStatus;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LostCenter() {
    }
}