package kr.lostory.backend.lostcenter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

@Getter
@Entity
@Table(name = "lost_centers")
public class LostCenter {

    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

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

    public LostCenter(
            String centerKey,
            String name,
            String parentPlace,
            String phoneNumber,
            String address,
            String detailLocation,
            BigDecimal latitude,
            BigDecimal longitude,
            String operatingHours,
            String handoffAvailable,
            String verificationStatus
    ) {
        this.centerKey = centerKey;
        synchronize(
                name,
                parentPlace,
                phoneNumber,
                address,
                detailLocation,
                latitude,
                longitude,
                operatingHours,
                handoffAvailable,
                verificationStatus
        );
        this.active = true;
        this.createdAt = this.updatedAt;
    }

    public void synchronize(
            String name,
            String parentPlace,
            String phoneNumber,
            String address,
            String detailLocation,
            BigDecimal latitude,
            BigDecimal longitude,
            String operatingHours,
            String handoffAvailable,
            String verificationStatus
    ) {
        this.name = name;
        this.parentPlace = parentPlace;
        this.phoneNumber = phoneNumber;
        this.address = address;
        this.detailLocation = detailLocation;
        this.location = GEOMETRY_FACTORY.createPoint(new Coordinate(
                longitude.doubleValue(),
                latitude.doubleValue()
        ));
        this.operatingHours = operatingHours;
        this.handoffAvailable = handoffAvailable;
        this.verificationStatus = verificationStatus;
        this.active = true;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void updateTimestamp() {
        this.updatedAt = Instant.now();
    }
}
