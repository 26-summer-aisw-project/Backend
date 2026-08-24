package kr.lostory.backend.lostcenter.presentation;

import java.math.BigDecimal;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository.NearbyLostCenterProjection;

public record NearbyLostCenterResponse(
        String id,
        String name,
        String contactPhone,
        LocationResponse location,
        double distanceMeters
) {

    public static NearbyLostCenterResponse from(NearbyLostCenterProjection projection) {
        return new NearbyLostCenterResponse(
                projection.getId().toString(),
                projection.getName(),
                projection.getPhoneNumber(),
                new LocationResponse(projection.getLatitude(), projection.getLongitude()),
                projection.getDistanceMeters()
        );
    }

    public record LocationResponse(BigDecimal latitude, BigDecimal longitude) {
    }
}
