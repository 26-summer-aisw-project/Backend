package kr.lostory.backend.founditem.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record AcceptHandoverRequest(
        @NotEmpty List<@Valid @NotBlank String> privateFeatures
) {
}
