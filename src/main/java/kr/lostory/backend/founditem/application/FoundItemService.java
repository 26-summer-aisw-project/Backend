package kr.lostory.backend.founditem.application;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.ItemFeature;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;
import kr.lostory.backend.founditem.domain.ItemFeatureRepository;
import kr.lostory.backend.founditem.domain.ItemFeatureSource;
import kr.lostory.backend.founditem.domain.ItemFeatureVisibility;
import kr.lostory.backend.founditem.domain.VisionStatus;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.founditem.presentation.FoundItemDetailResponse;
import kr.lostory.backend.founditem.presentation.FoundItemListResponse;
import kr.lostory.backend.founditem.presentation.FoundItemResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FoundItemService {

    private final FoundItemRepository foundItemRepository;
    private final ItemFeatureRepository featureRepository;

    public FoundItemService(FoundItemRepository foundItemRepository, ItemFeatureRepository featureRepository) {
        this.foundItemRepository = foundItemRepository;
        this.featureRepository = featureRepository;
    }

    @Transactional
    public FoundItemResponse register(CreateFoundItemCommand command) {
        validateStorage(command);
        validateLocation(command);

        FoundItem foundItem = new FoundItem(
                command.finderId(),
                command.name(),
                command.category(),
                command.description(),
                command.foundAt(),
                command.foundLatitude(),
                command.foundLongitude(),
                command.foundAddress(),
                command.foundLocationDetail(),
                command.storageMethod(),
                command.storageDescription(),
                command.handoverPlaceName()
        );

        FoundItem savedFoundItem = foundItemRepository.save(foundItem);

        return FoundItemResponse.from(savedFoundItem);
    }

    @Transactional(readOnly = true)
    public FoundItemResponse get(Long id, Long requesterId) {
        FoundItem foundItem = foundItemRepository.findById(id)
                .orElseThrow(() -> new LostoryException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "습득물이 찾아지지 않았습니다."
                ));

        if (!foundItem.getFinderId().equals(requesterId)) {
            throw new LostoryException(
                    ErrorCode.FORBIDDEN,
                    "본인이 등록한 습득물만 조회할 수 있습니다."
            );
        }

        return FoundItemResponse.from(foundItem);
    }

    @Transactional(readOnly = true)
    public FoundItemDetailResponse detail(Long id, Long requesterId, boolean admin) {
        FoundItem item = foundItemRepository.findById(id)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!admin && !item.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return FoundItemDetailResponse.from(item, suggestion(item));
    }

    @Transactional(readOnly = true)
    public FoundItemListResponse list(Long requesterId, FoundItemStatus status, int page, int pageSize) {
        PageRequest pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FoundItem> result = status == null
                ? foundItemRepository.findByFinderId(requesterId, pageable)
                : foundItemRepository.findByFinderIdAndStatus(requesterId, status, pageable);
        return FoundItemListResponse.from(result, page, pageSize);
    }

    private FoundItemDetailResponse.VisionSuggestion suggestion(FoundItem item) {
        if (item.getVisionStatus() != VisionStatus.READY) {
            return null;
        }
        String color = null;
        String description = null;
        for (ItemFeature feature : featureRepository
                .findByItemIdAndSourceAndVisibilityOrderByKindAscOrdinalAsc(
                        item.getId(), ItemFeatureSource.AI, ItemFeatureVisibility.MATCH_ONLY)) {
            if (feature.getKind() == ItemFeatureKind.COLOR && color == null) {
                color = feature.getFeatureValue();
            }
            if (feature.getKind() == ItemFeatureKind.PUBLIC_DESCRIPTION && description == null) {
                description = feature.getFeatureValue();
            }
        }
        return color == null && description == null
                ? null
                : new FoundItemDetailResponse.VisionSuggestion(color, description);
    }

    private void validateStorage(CreateFoundItemCommand command) {
        if (command.storageMethod() == StorageMethod.LEFT_IN_PLACE) {
            if (StringUtils.hasText(command.storageDescription())
                    || StringUtils.hasText(command.handoverPlaceName())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "storageDescription and handoverPlaceName must be empty when storageMethod is LEFT_IN_PLACE."
                );
            }
            return;
        }

        if (command.storageMethod() == StorageMethod.MOVED_TO_SAFE_PLACE) {
            if (!StringUtils.hasText(command.storageDescription())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "storageDescription is required when storageMethod is MOVED_TO_SAFE_PLACE."
                );
            }

            if (StringUtils.hasText(command.handoverPlaceName())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "handoverPlaceName must be empty when storageMethod is MOVED_TO_SAFE_PLACE."
                );
            }
            return;
        }

        if (command.storageMethod() == StorageMethod.HANDED_TO_CENTER) {
            if (StringUtils.hasText(command.storageDescription())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "storageDescription must be empty when storageMethod is HANDED_TO_CENTER."
                );
            }

            if (!StringUtils.hasText(command.handoverPlaceName())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "handoverPlaceName is required when storageMethod is HANDED_TO_CENTER."
                );
            }
        }
    }
    private void validateLocation(CreateFoundItemCommand command) {
        if (command.foundLatitude() == null || command.foundLongitude() == null) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "foundLatitude and foundLongitude are required."
            );
        }
    }
}
