package kr.lostory.backend.founditem.presentation;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import kr.lostory.backend.founditem.application.CreateFoundItemCommand;
import kr.lostory.backend.founditem.domain.StorageMethod;

public record CreateFoundItemRequest(
        @NotBlank
        @Size(max = 100)
        String name,

        @NotBlank
        @Size(max = 64)
        String category,

        @NotBlank
        @Size(max = 1000)
        String description,

        @NotNull
        OffsetDateTime foundAt,

        @NotNull
        @DecimalMin("-90.0")
        @DecimalMax("90.0")
        BigDecimal foundLatitude,

        @NotNull
        @DecimalMin("-180.0")
        @DecimalMax("180.0")
        BigDecimal foundLongitude,

        @Size(max = 255)
        String foundAddress,

        @Size(max = 255)
        String foundLocationDetail,

        @NotNull
        StorageMethod storageMethod,

        @Size(max = 1000)
        String storageDescription,

        @Size(max = 100)
        String handoverPlaceName
) {
    public CreateFoundItemCommand toCommand(Long finderId) {
        return new CreateFoundItemCommand(
                finderId,
                name,
                category,
                description,
                foundAt.toInstant(),
                foundLatitude,
                foundLongitude,
                blankToNull(foundAddress),
                blankToNull(foundLocationDetail),
                storageMethod,
                blankToNull(storageDescription),
                blankToNull(handoverPlaceName)
        );
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}