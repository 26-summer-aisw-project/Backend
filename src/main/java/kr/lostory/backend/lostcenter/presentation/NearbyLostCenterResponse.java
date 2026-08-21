package kr.lostory.backend.lostcenter.presentation;

import java.math.BigDecimal;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository.NearbyLostCenterProjection;

public record NearbyLostCenterResponse(
        Long id,
        String centerKey,
        String name,
        String parentPlace,
        String address,
        String detailLocation,
        String phoneNumber,
        String operatingHours,
        String verificationStatus,
        BigDecimal latitude,
        BigDecimal longitude,
        double distanceMeters
) {

    public static NearbyLostCenterResponse from(NearbyLostCenterProjection projection) {
        return new NearbyLostCenterResponse(
                projection.getId(),
                projection.getCenterKey(),
                projection.getName(),
                projection.getParentPlace(),
                projection.getAddress(),
                projection.getDetailLocation(),
                projection.getPhoneNumber(),
                projection.getOperatingHours(),
                projection.getVerificationStatus(),
                projection.getLatitude(),
                projection.getLongitude(),
                projection.getDistanceMeters()
        );
    }
}
