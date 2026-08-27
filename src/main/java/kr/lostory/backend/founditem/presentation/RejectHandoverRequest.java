package kr.lostory.backend.founditem.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectHandoverRequest(
        @NotBlank @Size(max = 1000) String reason
) {
}
