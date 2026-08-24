package kr.lostory.backend.founditem.presentation;

import java.time.Instant;
import kr.lostory.backend.founditem.domain.FoundItemImage;

public record FoundItemImageResponse(
        Long id,
        Long foundItemId,
        String contentType,
        Long sizeBytes,
        Instant createdAt
) {
    public static FoundItemImageResponse from(FoundItemImage image) {
        return new FoundItemImageResponse(
                image.getId(), image.getFoundItemId(), image.getContentType(), image.getSizeBytes(), image.getCreatedAt());
    }
}
