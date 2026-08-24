package kr.lostory.backend.founditem.application;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemImage;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemVisionJob;
import kr.lostory.backend.founditem.domain.FoundItemVisionJobRepository;
import kr.lostory.backend.founditem.domain.ObjectDeletionOutbox;
import kr.lostory.backend.founditem.domain.ObjectDeletionOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoundItemImagePersistenceService {

    private final FoundItemRepository foundItemRepository;
    private final FoundItemImageRepository imageRepository;
    private final FoundItemVisionJobRepository visionJobRepository;
    private final ObjectDeletionOutboxRepository deletionOutboxRepository;

    public FoundItemImagePersistenceService(
            FoundItemRepository foundItemRepository,
            FoundItemImageRepository imageRepository,
            FoundItemVisionJobRepository visionJobRepository,
            ObjectDeletionOutboxRepository deletionOutboxRepository
    ) {
        this.foundItemRepository = foundItemRepository;
        this.imageRepository = imageRepository;
        this.visionJobRepository = visionJobRepository;
        this.deletionOutboxRepository = deletionOutboxRepository;
    }

    @Transactional
    public FoundItemImage commitUpload(Long foundItemId, Long requesterId, PendingImage pending) {
        FoundItem item = foundItemRepository.findByIdForUpdate(foundItemId)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!item.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        imageRepository.findByFoundItemIdAndCurrentTrue(foundItemId).ifPresent(oldImage -> {
            oldImage.replace();
            imageRepository.flush();
            deletionOutboxRepository.save(new ObjectDeletionOutbox(
                    oldImage.getObjectKey(),
                    "found-item-image:" + oldImage.getId(),
                    "REPLACED"));
        });

        int generation = item.beginImageAnalysis();
        FoundItemImage image = imageRepository.saveAndFlush(new FoundItemImage(
                foundItemId,
                pending.originalFilename(),
                pending.objectKey(),
                pending.contentType(),
                pending.sizeBytes(),
                generation,
                pending.uploadOperationId()));
        visionJobRepository.save(new FoundItemVisionJob(foundItemId, image.getId(), generation));
        return image;
    }

    @Transactional
    public void enqueueCurrentForDeletion(Long foundItemId, String reason) {
        foundItemRepository.findByIdForUpdate(foundItemId)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        imageRepository.findByFoundItemIdAndCurrentTrue(foundItemId).ifPresent(image -> {
            image.replace();
            imageRepository.flush();
            deletionOutboxRepository.save(new ObjectDeletionOutbox(
                    image.getObjectKey(),
                    "found-item-image:" + image.getId(),
                    reason));
        });
    }

    public record PendingImage(
            String originalFilename,
            String objectKey,
            String contentType,
            long sizeBytes,
            java.util.UUID uploadOperationId
    ) {
    }
}
