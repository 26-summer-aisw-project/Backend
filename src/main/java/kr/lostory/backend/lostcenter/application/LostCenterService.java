package kr.lostory.backend.lostcenter.application;

import java.util.List;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import kr.lostory.backend.lostcenter.presentation.NearbyLostCenterResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LostCenterService {

    private static final int NEARBY_CENTER_LIMIT = 3;

    private final FoundItemRepository foundItemRepository;
    private final LostCenterRepository lostCenterRepository;

    public LostCenterService(
            FoundItemRepository foundItemRepository,
            LostCenterRepository lostCenterRepository
    ) {
        this.foundItemRepository = foundItemRepository;
        this.lostCenterRepository = lostCenterRepository;
    }

    @Transactional(readOnly = true)
    public List<NearbyLostCenterResponse> findNearbyByFoundItem(
            Long foundItemId,
            Long requesterId
    ) {
        FoundItem foundItem = foundItemRepository.findById(foundItemId)
                .orElseThrow(() -> new LostoryException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "습득물을 찾을 수 없습니다."
                ));

        if (!foundItem.getFinderId().equals(requesterId)) {
            throw new LostoryException(
                    ErrorCode.FORBIDDEN,
                    "본인이 등록한 습득물의 분실물센터 추천만 조회할 수 있습니다."
            );
        }

        if (foundItem.getFoundLatitude() == null || foundItem.getFoundLongitude() == null) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "습득물 위치 정보가 없어 가까운 분실물센터를 추천할 수 없습니다."
            );
        }

        return lostCenterRepository.findNearby(
                        foundItem.getFoundLatitude(),
                        foundItem.getFoundLongitude(),
                        NEARBY_CENTER_LIMIT
                )
                .stream()
                .map(NearbyLostCenterResponse::from)
                .toList();
    }
}