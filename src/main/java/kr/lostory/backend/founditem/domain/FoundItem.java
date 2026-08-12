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
import java.time.Instant;

@Entity
@Table(name = "found_items")
public class FoundItem {

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

    // 텍스트로 위치를 받으나, 추후 PostGIS 좌표 기반 위치로 변경 예정
    @Column(nullable = false, length = 255)
    private String foundLocationText;

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

    private Instant closedAt;

    protected FoundItem() {
    }

    public FoundItem(
            Long finderId,
            String name,
            String category,
            String description,
            Instant foundAt,
            String foundLocationText,
            StorageMethod storageMethod,
            String storageDescription,
            String handoverPlaceName
    ) {
        this.finderId = finderId;
        this.name = name;
        this.category = category;
        this.description = description;
        this.foundAt = foundAt;
        this.foundLocationText = foundLocationText;
        this.storageMethod = storageMethod;
        this.storageDescription = storageDescription;
        this.handoverPlaceName = handoverPlaceName;
        this.status = FoundItemStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void updateTimestamp() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getFinderId() {
        return finderId;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public Instant getFoundAt() {
        return foundAt;
    }

    public String getFoundLocationText() {
        return foundLocationText;
    }

    public StorageMethod getStorageMethod() {
        return storageMethod;
    }

    public String getStorageDescription() {
        return storageDescription;
    }

    public String getHandoverPlaceName() {
        return handoverPlaceName;
    }

    public FoundItemStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}