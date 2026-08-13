package kr.lostory.backend.founditem.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.StorageMethod;

public record FoundItemResponse(
        Long id,
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
        String handoverPlaceName,
        FoundItemStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant expiredAt
) {
    public static FoundItemResponse from(FoundItem foundItem) {
        return new FoundItemResponse(
                foundItem.getId(),
                foundItem.getFinderId(),
                foundItem.getName(),
                foundItem.getCategory(),
                foundItem.getDescription(),
                foundItem.getFoundAt(),
                foundItem.getFoundLatitude(),
                foundItem.getFoundLongitude(),
                foundItem.getFoundAddress(),
                foundItem.getFoundLocationDetail(),
                foundItem.getStorageMethod(),
                foundItem.getStorageDescription(),
                foundItem.getHandoverPlaceName(),
                foundItem.getStatus(),
                foundItem.getCreatedAt(),
                foundItem.getUpdatedAt(),
                foundItem.getExpiredAt()
        );
    }
}