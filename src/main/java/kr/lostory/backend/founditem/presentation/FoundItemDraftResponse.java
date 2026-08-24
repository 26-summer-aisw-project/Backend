package kr.lostory.backend.founditem.presentation;

import java.time.Instant;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.VisionStatus;

public record FoundItemDraftResponse(
        String id,
        FoundItemStatus status,
        VisionStatus visionStatus,
        Instant draftExpiresAt
) {
    public static FoundItemDraftResponse from(FoundItem item) {
        return new FoundItemDraftResponse(item.getId().toString(), item.getStatus(),
                item.getVisionStatus(), item.getDraftExpiresAt());
    }
}
