package kr.lostory.backend.lostcenter.presentation;

import kr.lostory.backend.lostcenter.domain.LostCenter;

public record AdminLostCenterResponse(
        String id,
        String name,
        String address,
        String contactPhone,
        LostCenterResponse.LocationResponse location,
        boolean isActive
) {
    public static AdminLostCenterResponse from(LostCenter center) {
        LostCenterResponse response = LostCenterResponse.from(center);
        return new AdminLostCenterResponse(response.id(), response.name(), response.address(),
                response.contactPhone(), response.location(), response.isActive());
    }
}
