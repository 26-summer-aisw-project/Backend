package kr.lostory.backend.founditem.presentation;

import java.time.Instant;
import java.util.List;
import kr.lostory.backend.founditem.domain.CenterHandover;
import kr.lostory.backend.founditem.domain.FoundItem;

public final class DashboardHandoverResponses {

    private DashboardHandoverResponses() {
    }

    public record ListResponse(List<Entry> data) {
    }

    public record Entry(String handoverId, String itemId, String category, Instant handedAt, String status) {
        public static Entry from(CenterHandover handover, FoundItem item) {
            return new Entry(handover.getId().toString(), item.getId().toString(), item.getCategory(),
                    handover.getUserConfirmedAt(), handover.getStatus().name());
        }
    }

    public record AcceptResponse(String handoverId, String itemId, String handoverStatus, Instant acceptedAt) {
        public static AcceptResponse from(CenterHandover handover) {
            return new AcceptResponse(handover.getId().toString(), handover.getFoundItemId().toString(),
                    handover.getStatus().name(), handover.getDecidedAt());
        }
    }

    public record RejectResponse(String handoverId, String handoverStatus) {
        public static RejectResponse from(CenterHandover handover) {
            return new RejectResponse(handover.getId().toString(), handover.getStatus().name());
        }
    }
}
