package kr.lostory.backend.founditem.application;

import java.time.Instant;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.founditem.domain.ObjectDeletionOutbox;
import kr.lostory.backend.founditem.domain.ObjectDeletionOutboxRepository;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectDeletionWorker {

    private final ObjectStorage storage;
    private final ObjectDeletionOutboxRepository outboxRepository;
    private final FoundItemImageRepository imageRepository;

    public ObjectDeletionWorker(
            ObjectStorage storage,
            ObjectDeletionOutboxRepository outboxRepository,
            FoundItemImageRepository imageRepository
    ) {
        this.storage = storage;
        this.outboxRepository = outboxRepository;
        this.imageRepository = imageRepository;
    }

    @Transactional
    public boolean processNext() {
        ObjectDeletionOutbox entry = outboxRepository.findFirstByStatusOrderByIdAsc("PENDING").orElse(null);
        if (entry == null) {
            return false;
        }
        if (storage.head(entry.getObjectKey()).isPresent()) {
            storage.delete(entry.getObjectKey());
        }
        Instant completedAt = Instant.now();
        imageRepository.findByObjectKey(entry.getObjectKey())
                .ifPresent(image -> image.markObjectDeleted(completedAt));
        entry.complete(completedAt);
        return true;
    }
}
