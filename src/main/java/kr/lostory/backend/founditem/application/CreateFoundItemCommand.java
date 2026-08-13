package kr.lostory.backend.founditem.application;

import java.time.Instant;
import kr.lostory.backend.founditem.domain.StorageMethod;

public record CreateFoundItemCommand(
        Long finderId,
        String name,
        String category,
        String description,
        Instant foundAt,
        String foundLocationText,
        StorageMethod storageMethod,
        String storageDescription,
        String handoverPlaceName
) {
}