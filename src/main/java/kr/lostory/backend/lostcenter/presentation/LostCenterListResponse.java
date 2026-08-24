package kr.lostory.backend.lostcenter.presentation;

import java.util.List;
import org.springframework.data.domain.Page;

public record LostCenterListResponse(List<LostCenterResponse> data, Meta meta) {
    public static LostCenterListResponse from(Page<kr.lostory.backend.lostcenter.domain.LostCenter> result,
                                               int page, int pageSize) {
        return new LostCenterListResponse(result.stream().map(LostCenterResponse::from).toList(),
                new Meta(page, pageSize, result.getTotalElements()));
    }

    public record Meta(int page, int pageSize, long totalItems) {
    }
}
