package kr.lostory.backend.founditem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;

@Getter
@Entity
@Table(name = "found_items")
public class FoundItem {

    private static final Duration DEFAULT_EXPIRATION_PERIOD = Duration.ofDays(14);
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "finder_id", nullable = false)
    private Long finderId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 64)
    private String category;

    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false)
    private Instant foundAt;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "found_location", columnDefinition = "geography(Point, 4326)")
    private Point foundLocation;

    @Column(length = 255)
    private String foundAddress;

    @Column(length = 255)
    private String foundLocationDetail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StorageMethod storageMethod;

    @Column(length = 1000)
    private String storageDescription;

    @Column(length = 100)
    private String handoverPlaceName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FoundItemStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    protected FoundItem() {
    }

    public FoundItem(
            Long finderId,
            String name,
            String category,
            String description,
            Instant foundAt,
            BigDecimal foundLatitude,
            BigDecimal foundLongitude,
            String foundAddress,
            String foundLocationDetail,
            StorageMethod storageMethod,
            String storageDescription,
            String handoverPlaceName
    ) {
        this.finderId = finderId;
        this.name = name;
        this.category = category;
        this.description = description;
        this.foundAt = foundAt;
        this.foundLocation = foundLatitude == null || foundLongitude == null
                ? null
                : GEOMETRY_FACTORY.createPoint(new Coordinate(
                        foundLongitude.doubleValue(),
                        foundLatitude.doubleValue()
                ));
        this.foundAddress = foundAddress;
        this.foundLocationDetail = foundLocationDetail;
        this.storageMethod = storageMethod;
        this.storageDescription = storageDescription;
        this.handoverPlaceName = handoverPlaceName;
        this.status = FoundItemStatus.ACTIVE;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.updatedAt = this.createdAt;
        this.expiredAt = this.createdAt.plus(DEFAULT_EXPIRATION_PERIOD);
    }

    public BigDecimal getFoundLatitude() {
        return foundLocation == null ? null : BigDecimal.valueOf(foundLocation.getY());
    }

    public BigDecimal getFoundLongitude() {
        return foundLocation == null ? null : BigDecimal.valueOf(foundLocation.getX());
    }

    @PreUpdate
    void updateTimestamp() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
