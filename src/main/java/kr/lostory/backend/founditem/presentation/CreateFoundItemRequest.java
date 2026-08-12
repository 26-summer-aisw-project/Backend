package kr.lostory.backend.founditem.presentation;

import kr.lostory.backend.founditem.application.CreateFoundItemCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
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

        @NotBlank
        @Size(max = 255)
        String foundLocationText,

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
                foundLocationText,
                storageMethod,
                storageDescription,
                handoverPlaceName
        );
    }
}