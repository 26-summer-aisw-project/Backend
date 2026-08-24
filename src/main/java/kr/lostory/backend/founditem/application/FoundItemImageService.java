package kr.lostory.backend.founditem.application;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.common.storage.ObjectStorageException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemImage;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.presentation.FoundItemImageResponse;
import kr.lostory.backend.config.FoundItemProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FoundItemImageService {

    private static final long MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final FoundItemRepository foundItemRepository;
    private final FoundItemImageRepository imageRepository;
    private final FoundItemImagePersistenceService persistenceService;
    private final VisionDailyAdmissionService admissionService;
    private final ObjectStorage storage;
    private final FoundItemProperties properties;
    private final Clock clock;

    public FoundItemImageService(
            FoundItemRepository foundItemRepository,
            FoundItemImageRepository imageRepository,
            FoundItemImagePersistenceService persistenceService,
            VisionDailyAdmissionService admissionService,
            ObjectStorage storage,
            FoundItemProperties properties,
            Clock clock
    ) {
        this.foundItemRepository = foundItemRepository;
        this.imageRepository = imageRepository;
        this.persistenceService = persistenceService;
        this.admissionService = admissionService;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    public FoundItem createDraft(Long finderId, MultipartFile image) {
        ValidatedImage validated = validate(image);
        VisionDailyAdmissionService.Admission admission = admissionService.reserve();
        UUID operationId = UUID.randomUUID();
        String key = "found-items/" + UUID.randomUUID();
        try {
            storage.put(key, validated.bytes(), validated.contentType(), operationId);
        } catch (ObjectStorageException exception) {
            admissionService.release(admission);
            throw new LostoryException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        try {
            Instant createdAt = clock.instant();
            return persistenceService.createDraft(finderId, createdAt,
                    createdAt.plus(properties.draftTtl()),
                    new FoundItemImagePersistenceService.PendingImage(
                            validated.originalFilename(), key, validated.contentType(),
                            validated.bytes().length, operationId));
        } catch (RuntimeException exception) {
            compensate(key);
            admissionService.release(admission);
            throw exception;
        }
    }

    public FoundItemImageResponse upload(Long foundItemId, Long requesterId, MultipartFile image) {
        FoundItem item = foundItemRepository.findById(foundItemId)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!item.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!item.isRegistrationMutable()) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }

        ValidatedImage validated = validate(image);
        VisionDailyAdmissionService.Admission admission = admissionService.reserve();
        UUID operationId = UUID.randomUUID();
        String key = "found-items/" + UUID.randomUUID();
        try {
            storage.put(key, validated.bytes(), validated.contentType(), operationId);
        } catch (ObjectStorageException exception) {
            admissionService.release(admission);
            throw new LostoryException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        try {
            FoundItemImage saved = persistenceService.commitUpload(foundItemId, requesterId,
                    new FoundItemImagePersistenceService.PendingImage(
                            validated.originalFilename(), key, validated.contentType(),
                            validated.bytes().length, operationId));
            return FoundItemImageResponse.from(saved);
        } catch (RuntimeException exception) {
            compensate(key);
            admissionService.release(admission);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public ObjectStorage.StoredObject getCurrent(Long foundItemId, Long requesterId, boolean admin) {
        FoundItem item = foundItemRepository.findById(foundItemId)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!admin && !item.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        FoundItemImage image = imageRepository.findByFoundItemIdAndCurrentTrue(foundItemId)
                .orElseThrow(() -> unavailable(item));
        if (storage.head(image.getObjectKey()).isEmpty()) {
            throw unavailable(item);
        }
        try {
            return storage.get(image.getObjectKey());
        } catch (ObjectStorageException exception) {
            throw unavailable(item);
        }
    }

    private LostoryException unavailable(FoundItem item) {
        return new LostoryException(item.isTerminal() ? ErrorCode.MEDIA_NOT_AVAILABLE : ErrorCode.RESOURCE_NOT_FOUND);
    }

    private ValidatedImage validate(MultipartFile image) {
        if (image == null || image.isEmpty()) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST, "Empty image file is not allowed.");
        }
        if (image.getSize() > MAX_IMAGE_SIZE_BYTES || !ALLOWED_CONTENT_TYPES.contains(image.getContentType())) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        byte[] bytes;
        try {
            bytes = image.getBytes();
        } catch (IOException exception) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        String detected = detectContentType(bytes);
        if (!detected.equals(image.getContentType())) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        String filename = image.getOriginalFilename();
        return new ValidatedImage(bytes, detected, filename == null || filename.isBlank() ? "image" : filename);
    }

    private String detectContentType(byte[] bytes) {
        if (startsWith(bytes, new int[]{0xff, 0xd8, 0xff})) {
            return "image/jpeg";
        }
        if (startsWith(bytes, new int[]{0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return "image/png";
        }
        if (bytes.length >= 12 && startsWith(bytes, new int[]{'R', 'I', 'F', 'F'})
                && Arrays.equals(Arrays.copyOfRange(bytes, 8, 12), new byte[]{'W', 'E', 'B', 'P'})) {
            return "image/webp";
        }
        throw new LostoryException(ErrorCode.INVALID_REQUEST);
    }

    private boolean startsWith(byte[] bytes, int[] magic) {
        if (bytes.length < magic.length) {
            return false;
        }
        for (int index = 0; index < magic.length; index++) {
            if ((bytes[index] & 0xff) != magic[index]) {
                return false;
            }
        }
        return true;
    }

    private void compensate(String key) {
        try {
            storage.delete(key);
        } catch (ObjectStorageException ignored) {
        }
    }

    private record ValidatedImage(byte[] bytes, String contentType, String originalFilename) {
    }
}
