package kr.lostory.backend.founditem.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemImage;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.presentation.FoundItemImageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class FoundItemImageService {

    private static final int MAX_IMAGE_COUNT = 5;
    private static final long MAX_IMAGE_SIZE_BYTES = 5 * 1024 * 1024;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg",
            "image/png",
            "image/webp"
    );
    private static final Path UPLOAD_ROOT = Path.of("uploads", "found-items");

    private final FoundItemRepository foundItemRepository;
    private final FoundItemImageRepository foundItemImageRepository;

    @Transactional
    public List<FoundItemImageResponse> upload(Long foundItemId, Long requesterId, List<MultipartFile> images) {
        FoundItem foundItem = foundItemRepository.findById(foundItemId)
                .orElseThrow(() -> new LostoryException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "Found item not found."
                ));

        if (!foundItem.getFinderId().equals(requesterId)) {
            throw new LostoryException(
                    ErrorCode.FORBIDDEN,
                    "Only the finder can upload images for this found item."
            );
        }

        validateImages(foundItemId, images);

        return images.stream()
                .map(image -> saveImage(foundItemId, image))
                .map(FoundItemImageResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FoundItemImageResponse> getImages(Long foundItemId) {
        if (!foundItemRepository.existsById(foundItemId)) {
            throw new LostoryException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Found item not found."
            );
        }

        return foundItemImageRepository.findAllByFoundItemIdOrderByCreatedAtAsc(foundItemId)
                .stream()
                .map(FoundItemImageResponse::from)
                .toList();
    }

    private void validateImages(Long foundItemId, List<MultipartFile> images) {
        if (images == null || images.isEmpty()) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "At least one image is required."
            );
        }

        long currentImageCount = foundItemImageRepository.countByFoundItemId(foundItemId);
        if (currentImageCount + images.size() > MAX_IMAGE_COUNT) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "A found item can have up to 5 images."
            );
        }

        for (MultipartFile image : images) {
            validateImage(image);
        }
    }

    private void validateImage(MultipartFile image) {
        if (image.isEmpty()) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "Empty image file is not allowed."
            );
        }

        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "Image size must be 5MB or less."
            );
        }

        if (!ALLOWED_CONTENT_TYPES.contains(image.getContentType())) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "Only JPEG, PNG, and WEBP images are allowed."
            );
        }
    }

    private FoundItemImage saveImage(Long foundItemId, MultipartFile image) {
        String originalFilename = image.getOriginalFilename();
        String storedFilename = UUID.randomUUID() + getExtension(originalFilename);
        Path directory = UPLOAD_ROOT.resolve(foundItemId.toString());
        Path storagePath = directory.resolve(storedFilename);

        try {
            Files.createDirectories(directory);
            image.transferTo(storagePath);
        } catch (IOException exception) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "Failed to store image file."
            );
        }

        FoundItemImage foundItemImage = new FoundItemImage(
                foundItemId,
                originalFilename,
                storedFilename,
                storagePath.toString(),
                image.getContentType(),
                image.getSize()
        );

        return foundItemImageRepository.save(foundItemImage);
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }

        return filename.substring(filename.lastIndexOf("."));
    }
}