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

    @Column(name = "source_key", unique = true)
    private String sourceKey;

    @Column(nullable = false)
    private String name;

    @Column(name = "parent_place")
    private String parentPlace;

    @Column(nullable = false)
    private String address;

    @Column(name = "detail_location")
    private String detailLocation;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(nullable = false, columnDefinition = "geography(Point, 4326)")
    private Point location;

    @Column(name = "contact_phone", nullable = false)
    private String contactPhone;

    @Column(name = "operating_hours", nullable = false)
    private String operatingHours;

    @Column(name = "verification_status", nullable = false)
    private String verificationStatus;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "is_csv_managed", nullable = false)
    private boolean csvManaged;

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
        this.verificationStatus = "inactive";
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public LostCenter(
            String sourceKey,
            String name,
            String parentPlace,
            String address,
            String detailLocation,
            BigDecimal latitude,
            BigDecimal longitude,
            String contactPhone,
            String operatingHours,
            String verificationStatus
    ) {
        this.sourceKey = sourceKey;
        synchronize(
                name,
                parentPlace,
                address,
                detailLocation,
                latitude,
                longitude,
                contactPhone,
                operatingHours,
                verificationStatus
        );
        this.active = true;
        this.createdAt = this.updatedAt;
    }

    public void synchronize(
            String name,
            String parentPlace,
            String address,
            String detailLocation,
            BigDecimal latitude,
            BigDecimal longitude,
            String contactPhone,
            String operatingHours,
            String verificationStatus
    ) {
        this.name = name;
        this.parentPlace = parentPlace;
        this.address = address;
        this.detailLocation = detailLocation;
        this.location = GEOMETRY_FACTORY.createPoint(new Coordinate(
                longitude.doubleValue(),
                latitude.doubleValue()
        ));
        this.contactPhone = contactPhone;
        this.operatingHours = operatingHours;
        this.verificationStatus = verificationStatus;
        this.active = true;
        this.csvManaged = true;
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void updateTimestamp() {
        this.updatedAt = Instant.now();
    }
}
