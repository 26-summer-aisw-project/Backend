package kr.lostory.backend.founditem.presentation;

import java.time.Instant;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.HandoverStatus;
import kr.lostory.backend.founditem.domain.VisionStatus;

public record FoundItemDetailResponse(
        String id,
        FoundItemStatus status,
        HandoverStatus handoverStatus,
        VisionStatus visionStatus,
        VisionSuggestion visionSuggestion,
        Instant draftExpiresAt
) {
    public static FoundItemDetailResponse from(FoundItem item, VisionSuggestion suggestion) {
        return new FoundItemDetailResponse(item.getId().toString(), item.getStatus(),
                publicHandoverStatus(item.getHandoverStatus()), item.getVisionStatus(), suggestion, item.getDraftExpiresAt());
    }

    private static HandoverStatus publicHandoverStatus(HandoverStatus handoverStatus) {
        return handoverStatus == HandoverStatus.LEGACY_UNVERIFIED ? HandoverStatus.NONE : handoverStatus;
    }

    public record VisionSuggestion(String color, String publicDescription) {
    }
}
