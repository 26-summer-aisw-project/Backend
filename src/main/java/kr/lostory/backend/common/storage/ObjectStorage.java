package kr.lostory.backend.common.storage;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ObjectStorage {

    void put(String key, byte[] bytes, String contentType, UUID uploadOperationId);

    StoredObject get(String key);

    PresignedGet presignGet(String key, Instant expiresAt);

    Optional<ObjectMetadata> head(String key);

    void delete(String key);

    List<ObjectMetadata> list(String prefix);

    record StoredObject(byte[] bytes, String contentType) {
    }

    record PresignedGet(URI url, Instant expiresAt) {
    }

    record ObjectMetadata(
            String key,
            String contentType,
            long sizeBytes,
            UUID uploadOperationId,
            Instant createdAt
    ) {
    }
}
