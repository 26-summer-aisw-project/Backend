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

    @Column(length = 100)
    private String name;

    @Column(length = 64)
    private String category;

    @Column(length = 1000)
    private String description;

    @Column
    private Instant foundAt;

    @JdbcTypeCode(SqlTypes.GEOGRAPHY)
    @Column(name = "found_location", columnDefinition = "geography(Point, 4326)")
    private Point foundLocation;

    @Column(length = 255)
    private String foundAddress;

    @Column(length = 255)
    private String foundLocationDetail;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private StorageMethod storageMethod;

    @Column(length = 1000)
    private String storageDescription;

    @Column(name = "legacy_handover_place_name", length = 100)
    private String legacyHandoverPlaceName;

    @Column(name = "center_id")
    private Long centerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "handover_status", nullable = false)
    private HandoverStatus handoverStatus;

    @Column(name = "handed_at")
    private Instant handedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private FoundItemStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "vision_status", nullable = false)
    private VisionStatus visionStatus;

    @Column(name = "analysis_generation", nullable = false)
    private int analysisGeneration;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "draft_expires_at")
    private Instant draftExpiresAt;

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
            String legacyHandoverPlaceName
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
        this.legacyHandoverPlaceName = legacyHandoverPlaceName;
        this.handoverStatus = storageMethod == StorageMethod.HANDED_TO_CENTER
                ? HandoverStatus.LEGACY_UNVERIFIED
                : HandoverStatus.NONE;
        this.status = FoundItemStatus.ACTIVE;
        this.visionStatus = VisionStatus.FAILED;
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

    public String getHandoverPlaceName() {
        return legacyHandoverPlaceName;
    }

    public int beginImageAnalysis() {
        analysisGeneration++;
        visionStatus = VisionStatus.PENDING;
        return analysisGeneration;
    }

    public boolean isTerminal() {
        return status == FoundItemStatus.EXPIRED || status == FoundItemStatus.RETURNED;
    }

    @PreUpdate
    void updateTimestamp() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
