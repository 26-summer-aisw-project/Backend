package kr.lostory.backend.common.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "object-storage.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledObjectStorage implements ObjectStorage {

    private ObjectStorageException disabled() {
        return new ObjectStorageException("Object storage is disabled.");
    }

    @Override
    public void put(String key, byte[] bytes, String contentType, UUID uploadOperationId) {
        throw disabled();
    }

    @Override
    public StoredObject get(String key) {
        throw disabled();
    }

    @Override
    public Optional<ObjectMetadata> head(String key) {
        throw disabled();
    }

    @Override
    public void delete(String key) {
        throw disabled();
    }

    @Override
    public List<ObjectMetadata> list(String prefix) {
        return List.of();
    }
}
