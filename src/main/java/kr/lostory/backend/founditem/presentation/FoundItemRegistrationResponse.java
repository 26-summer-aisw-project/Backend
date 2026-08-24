package kr.lostory.backend.founditem.presentation;

import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.HandoverStatus;
import kr.lostory.backend.founditem.domain.StorageMethod;

public record FoundItemRegistrationResponse(
        String id,
        FoundItemStatus status,
        StorageMethod storageMethod,
        String centerId,
        HandoverStatus handoverStatus
) {
    public static FoundItemRegistrationResponse from(FoundItem item) {
        return new FoundItemRegistrationResponse(
                item.getId().toString(),
                item.getStatus(),
                item.getStorageMethod(),
                item.getCenterId() == null ? null : item.getCenterId().toString(),
                item.getHandoverStatus());
    }
}
