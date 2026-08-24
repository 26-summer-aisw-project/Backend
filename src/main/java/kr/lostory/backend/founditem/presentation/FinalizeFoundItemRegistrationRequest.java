package kr.lostory.backend.founditem.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Null;
import java.math.BigDecimal;
import java.time.Instant;
import kr.lostory.backend.founditem.domain.StorageMethod;

public record FinalizeFoundItemRegistrationRequest(
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String category,
        @NotNull Instant foundAt,
        @NotNull @Valid FoundLocation foundLocation,
        @NotNull @Valid ConfirmedFeatures confirmedFeatures,
        @NotNull StorageMethod storageMethod,
        String centerId,
        @Size(max = 1000) String storageDescription,
        @Null Instant handedAt
) {
    public record FoundLocation(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude
    ) {
    }

    public record ConfirmedFeatures(
            @NotBlank @Pattern(regexp = "BLACK|WHITE|GRAY|BROWN|RED|ORANGE|YELLOW|GREEN|BLUE|PURPLE|PINK|BEIGE|SILVER|GOLD")
            String color,
            @NotBlank @Size(max = 1000) String publicDescription
    ) {
    }
}
