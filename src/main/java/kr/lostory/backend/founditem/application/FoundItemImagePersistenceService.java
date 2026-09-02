package kr.lostory.backend.founditem.application;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.audit.application.P0AuditService;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemImage;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemVisionJob;
import kr.lostory.backend.founditem.domain.FoundItemVisionJobRepository;
import kr.lostory.backend.founditem.domain.ObjectDeletionOutbox;
import kr.lostory.backend.founditem.domain.ObjectDeletionOutboxRepository;
import kr.lostory.backend.founditem.domain.ItemFeatureRepository;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;
import kr.lostory.backend.founditem.domain.ItemFeatureSource;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class FoundItemImagePersistenceService {

    private final FoundItemRepository foundItemRepository;
    private final FoundItemImageRepository imageRepository;
    private final FoundItemVisionJobRepository visionJobRepository;
    private final ObjectDeletionOutboxRepository deletionOutboxRepository;
    private final ItemFeatureRepository featureRepository;
    private final LostReportRepository reportRepository;
    private final P0AuditService audit;
    private final FoundItemLifecycleCleanupService lifecycle;

    public FoundItemImagePersistenceService(
            FoundItemRepository foundItemRepository,
            FoundItemImageRepository imageRepository,
            FoundItemVisionJobRepository visionJobRepository,
            ObjectDeletionOutboxRepository deletionOutboxRepository,
            ItemFeatureRepository featureRepository,
            LostReportRepository reportRepository,
            P0AuditService audit,
            FoundItemLifecycleCleanupService lifecycle
    ) {
        this.foundItemRepository = foundItemRepository;
        this.imageRepository = imageRepository;
        this.visionJobRepository = visionJobRepository;
        this.deletionOutboxRepository = deletionOutboxRepository;
        this.featureRepository = featureRepository;
        this.reportRepository = reportRepository;
        this.audit = audit;
        this.lifecycle = lifecycle;
    }

    @Transactional(noRollbackFor = LostoryException.class)
    public FoundItemImage commitUpload(Long foundItemId, Long requesterId, PendingImage pending) {
        FoundItem item = foundItemRepository.findByIdForUpdate(foundItemId)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!item.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        lifecycle.admit(foundItemId);
        if (!item.isRegistrationMutable()) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }

        visionJobRepository.supersedePendingByFoundItemId(foundItemId);
        Optional<FoundItemImage> replacedImage = imageRepository.findByFoundItemIdAndCurrentTrue(foundItemId);
        replacedImage.ifPresent(oldImage -> {
            oldImage.replace();
            imageRepository.flush();
            deletionOutboxRepository.save(new ObjectDeletionOutbox(
                    oldImage.getObjectKey(),
                    "found-item-image:" + oldImage.getId(),
                    "REPLACED"));
        });

        featureRepository.deleteByItemIdAndSource(foundItemId, ItemFeatureSource.AI);
        featureRepository.deleteByItemIdAndSourceAndKinds(
                foundItemId,
                ItemFeatureSource.FINDER,
                List.of(ItemFeatureKind.COLOR, ItemFeatureKind.PUBLIC_DESCRIPTION));
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
        reportRepository.markOpenCandidatesStale();
        if (replacedImage.isPresent()) {
            audit.foundItemImageReplaced(requesterId, foundItemId);
        }
        return image;
    }

    @Transactional
    public FoundItem createDraft(Long finderId, Instant createdAt, Instant draftExpiresAt, PendingImage pending) {
        FoundItem item = foundItemRepository.saveAndFlush(FoundItem.draft(finderId, createdAt, draftExpiresAt));
        int generation = item.beginImageAnalysis();
        FoundItemImage image = imageRepository.saveAndFlush(new FoundItemImage(
                item.getId(), pending.originalFilename(), pending.objectKey(), pending.contentType(),
                pending.sizeBytes(), generation, pending.uploadOperationId()));
        visionJobRepository.save(new FoundItemVisionJob(item.getId(), image.getId(), generation));
        return item;
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
