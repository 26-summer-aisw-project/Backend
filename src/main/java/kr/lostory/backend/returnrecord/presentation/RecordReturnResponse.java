package kr.lostory.backend.returnrecord.presentation;

import kr.lostory.backend.returnrecord.domain.ReturnRecord;

public record RecordReturnResponse(
        String returnId,
        String itemId,
        String reportId,
        String status,
        int rewardGranted
) {
    public static RecordReturnResponse from(ReturnRecord record) {
        return new RecordReturnResponse(
                record.getId().toString(),
                record.getFoundItemId().toString(),
                record.getLostReportId().toString(),
                "RETURNED",
                5);
    }
}
