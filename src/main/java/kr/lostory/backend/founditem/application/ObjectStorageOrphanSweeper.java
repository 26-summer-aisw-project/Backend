package kr.lostory.backend.founditem.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.config.ObjectStorageProperties;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class ObjectStorageOrphanSweeper {

    private final ObjectStorage storage;
    private final FoundItemImageRepository imageRepository;
    private final Duration grace;
    private final Clock clock;

    public ObjectStorageOrphanSweeper(
            ObjectStorage storage,
            FoundItemImageRepository imageRepository,
            ObjectStorageProperties properties,
            Clock clock
    ) {
        this.storage = storage;
        this.imageRepository = imageRepository;
        this.grace = properties.orphanGrace();
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${object-storage.orphan-sweep-interval}",
            initialDelayString = "${object-storage.orphan-sweep-initial-delay}"
    )
    public void scheduledSweep() {
        sweep(clock.instant());
    }

    public int sweep(Instant now) {
        int deleted = 0;
        for (ObjectStorage.ObjectMetadata object : storage.list("found-items/")) {
            if (object.uploadOperationId() != null
                    && object.createdAt().isBefore(now.minus(grace))
                    && !imageRepository.existsByUploadOperationId(object.uploadOperationId())) {
                storage.delete(object.key());
                deleted++;
            }
        }
        return deleted;
    }
}
