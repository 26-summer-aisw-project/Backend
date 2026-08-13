package kr.lostory.backend.founditem.presentation;

import java.time.Instant;
import kr.lostory.backend.founditem.domain.FoundItemImage;

public record FoundItemImageResponse(
        Long id,
        Long foundItemId,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String imageUrl,
        Instant createdAt
) {

    public static FoundItemImageResponse from(FoundItemImage image) {
        return new FoundItemImageResponse(
                image.getId(),
                image.getFoundItemId(),
                image.getOriginalFilename(),
                image.getContentType(),
                image.getSizeBytes(),
                "/api/v1/found-items/" + image.getFoundItemId() + "/images/" + image.getId(),
                image.getCreatedAt()
        );
    }
}