package kr.lostory.backend.lostcenter.application;

import java.math.BigDecimal;
import java.util.Set;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.config.LostCenterProperties;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.lostcenter.domain.LostCenter;
import kr.lostory.backend.lostcenter.domain.LostCenterDetails;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import kr.lostory.backend.lostcenter.presentation.AdminLostCenterResponse;
import kr.lostory.backend.lostcenter.presentation.CenterLocationRequest;
import kr.lostory.backend.lostcenter.presentation.CreateLostCenterRequest;
import kr.lostory.backend.lostcenter.presentation.LostCenterListResponse;
import kr.lostory.backend.lostcenter.presentation.NearbyLostCenterListResponse;
import kr.lostory.backend.lostcenter.presentation.NearbyLostCenterResponse;
import kr.lostory.backend.lostcenter.presentation.UpdateLostCenterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LostCenterService {
    private static final Set<String> DIRECTORY_STATUSES = Set.of(
            "official_verified", "official_board_verified", "official_local_verified", "admin_verified");
    private static final int P0_NEARBY_RADIUS_METERS = 1000;
    private static final int MAX_NEARBY_RESULTS = 10;

    private final FoundItemRepository foundItemRepository;
    private final LostCenterRepository lostCenterRepository;
    private final LostCenterProperties lostCenterProperties;

    @Transactional(readOnly = true)
    public LostCenterListResponse list(int page, int pageSize, String query) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw invalid("page는 1 이상, pageSize는 1 이상 100 이하여야 합니다.");
        }
        String normalizedQuery = query == null || query.isBlank() ? "" : query.strip();
        return LostCenterListResponse.from(lostCenterRepository.findDirectory(
                DIRECTORY_STATUSES, normalizedQuery, PageRequest.of(page - 1, pageSize)), page, pageSize);
    }

    @Transactional(readOnly = true)
    public NearbyLostCenterListResponse findNearby(BigDecimal latitude, BigDecimal longitude) {
        validateCoordinates(latitude, longitude);
        return nearby(latitude, longitude);
    }

    @Transactional(readOnly = true)
    public NearbyLostCenterListResponse findNearbyByFoundItem(Long foundItemId, Long requesterId) {
        FoundItem foundItem = foundItemRepository.findById(foundItemId)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND, "습득물을 찾을 수 없습니다."));
        if (!foundItem.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND, "습득물을 찾을 수 없습니다.");
        }
        if (foundItem.getFoundLatitude() == null || foundItem.getFoundLongitude() == null) {
            throw invalid("습득물 위치 정보가 없어 가까운 분실물센터를 추천할 수 없습니다.");
        }
        return nearby(foundItem.getFoundLatitude(), foundItem.getFoundLongitude());
    }

    @Transactional
    public AdminLostCenterResponse create(CreateLostCenterRequest request) {
        CenterLocationRequest location = request.location();
        LostCenter center = LostCenter.adminVerified(new LostCenterDetails(
                request.name().strip(), request.address().strip(), location.latitude(), location.longitude(),
                request.contactPhone().strip(), true));
        return AdminLostCenterResponse.from(lostCenterRepository.save(center));
    }

    @Transactional
    public AdminLostCenterResponse update(Long centerId, UpdateLostCenterRequest request) {
        LostCenter center = lostCenterRepository.findById(centerId)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND,
                        "분실물센터를 찾을 수 없습니다."));
        if (center.isCsvManaged()) {
            throw invalid("CSV로 관리되는 분실물센터는 관리자 API에서 수정할 수 없습니다.");
        }
        if (request.name() == null && request.address() == null && request.contactPhone() == null
                && request.location() == null && request.isActive() == null) {
            throw invalid("변경할 분실물센터 정보가 없습니다.");
        }
        if (isBlank(request.name()) || isBlank(request.address()) || isBlank(request.contactPhone())) {
            throw invalid("분실물센터 문자열 정보는 공백일 수 없습니다.");
        }
        CenterLocationRequest location = request.location();
        center.updateDirectoryEntry(new LostCenterDetails(strip(request.name()), strip(request.address()),
                location == null ? null : location.latitude(), location == null ? null : location.longitude(),
                strip(request.contactPhone()), request.isActive()));
        return AdminLostCenterResponse.from(center);
    }

    private NearbyLostCenterListResponse nearby(BigDecimal latitude, BigDecimal longitude) {
        int limit = Math.min(MAX_NEARBY_RESULTS, lostCenterProperties.nearbyLimit());
        return new NearbyLostCenterListResponse(lostCenterRepository.findNearby(latitude, longitude,
                        P0_NEARBY_RADIUS_METERS, limit).stream()
                .map(NearbyLostCenterResponse::from).toList());
    }

    private static void validateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw invalid("위도 또는 경도가 유효한 범위를 벗어났습니다.");
        }
    }

    private static String strip(String value) {
        return value == null ? null : value.strip();
    }

    private static boolean isBlank(String value) {
        return value != null && value.isBlank();
    }

    private static LostoryException invalid(String message) {
        return new LostoryException(ErrorCode.INVALID_REQUEST, message);
    }
}
