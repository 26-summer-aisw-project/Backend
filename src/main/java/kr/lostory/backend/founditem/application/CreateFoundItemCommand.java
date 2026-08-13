package kr.lostory.backend.founditem.application;

import java.math.BigDecimal;
import java.time.Instant;
import kr.lostory.backend.founditem.domain.StorageMethod;

public record CreateFoundItemCommand(
        Long finderId,
        String name,
        String category,
        String description,
        Instant foundAt,
        BigDecimal foundLatitude,
        BigDecimal foundLongitude,
        String foundAddress,
        String foundLocationDetail,
        StorageMethod storageMethod,
        String storageDescription,
        String handoverPlaceName
) {
}