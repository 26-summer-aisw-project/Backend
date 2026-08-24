package kr.lostory.backend.lostcenter.presentation;

import java.math.BigDecimal;
import kr.lostory.backend.lostcenter.domain.LostCenter;

public record LostCenterResponse(
        String id,
        String name,
        String address,
        String contactPhone,
        LocationResponse location,
        boolean isActive
) {
    public static LostCenterResponse from(LostCenter center) {
        return new LostCenterResponse(center.getId().toString(), center.getName(), center.getAddress(),
                center.getContactPhone(), new LocationResponse(
                        BigDecimal.valueOf(center.getLocation().getY()),
                        BigDecimal.valueOf(center.getLocation().getX())), center.isActive());
    }

    public record LocationResponse(BigDecimal latitude, BigDecimal longitude) {
    }
}
