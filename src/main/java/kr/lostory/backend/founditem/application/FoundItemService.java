package kr.lostory.backend.founditem.application;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.founditem.presentation.FoundItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FoundItemService {

    private final FoundItemRepository foundItemRepository;

    public FoundItemService(FoundItemRepository foundItemRepository) {
        this.foundItemRepository = foundItemRepository;
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
