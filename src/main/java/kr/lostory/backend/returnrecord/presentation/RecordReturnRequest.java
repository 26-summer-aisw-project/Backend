package kr.lostory.backend.returnrecord.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RecordReturnRequest(
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") String itemId,
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") String reportId
) {
}
