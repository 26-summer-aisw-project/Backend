package kr.lostory.backend.founditem.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "found_item_images")
public class FoundItemImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "found_item_id", nullable = false)
    private Long foundItemId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "stored_filename", length = 255)
    private String storedFilename;

    @Column(name = "legacy_storage_path", length = 500)
    private String storagePath;

    @Column(name = "object_key", length = 500)
    private String objectKey;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    @Column(name = "analysis_generation", nullable = false)
    private int analysisGeneration;

    @Column(name = "upload_operation_id", unique = true)
    private java.util.UUID uploadOperationId;

    @Column(name = "object_deleted_at")
    private Instant objectDeletedAt;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    @Column(nullable = false)
    private Instant createdAt;

    protected FoundItemImage() {
    }

    public FoundItemImage(
            Long foundItemId,
            String originalFilename,
            String storedFilename,
            String storagePath,
            String contentType,
            Long sizeBytes
    ) {
        this.foundItemId = foundItemId;
        this.originalFilename = originalFilename;
        this.storedFilename = storedFilename;
        this.storagePath = storagePath;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdAt = Instant.now();
    }

    public FoundItemImage(
            Long foundItemId,
            String originalFilename,
            String objectKey,
            String contentType,
            long sizeBytes,
            int analysisGeneration,
            java.util.UUID uploadOperationId
    ) {
        this.foundItemId = foundItemId;
        this.originalFilename = originalFilename;
        this.objectKey = objectKey;
        this.current = true;
        this.analysisGeneration = analysisGeneration;
        this.uploadOperationId = uploadOperationId;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdAt = Instant.now();
    }

    public void replace() {
        current = false;
    }

    public void markObjectDeleted(Instant deletedAt) {
        current = false;
        objectDeletedAt = deletedAt;
    }
}
