package kr.lostory.backend.founditem.presentation;

import java.util.List;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.HandoverStatus;
import kr.lostory.backend.founditem.domain.VisionStatus;
import org.springframework.data.domain.Page;

public record FoundItemListResponse(List<Item> data, Meta meta) {
    public static FoundItemListResponse from(Page<FoundItem> page, int requestedPage, int pageSize) {
        return new FoundItemListResponse(page.getContent().stream().map(Item::from).toList(),
                new Meta(requestedPage, pageSize, page.getTotalElements()));
    }

    public record Item(
            String id,
            FoundItemStatus status,
            VisionStatus visionStatus,
            String category,
            HandoverStatus handoverStatus
    ) {
        static Item from(FoundItem item) {
            return new Item(item.getId().toString(), item.getStatus(), item.getVisionStatus(),
                    item.getCategory(), publicHandoverStatus(item.getHandoverStatus()));
        }

        private static HandoverStatus publicHandoverStatus(HandoverStatus handoverStatus) {
            return handoverStatus == HandoverStatus.LEGACY_UNVERIFIED ? HandoverStatus.NONE : handoverStatus;
        }
    }

    public record Meta(int page, int pageSize, long totalItems) {
    }
}
