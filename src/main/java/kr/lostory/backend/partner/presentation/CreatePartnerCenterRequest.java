package kr.lostory.backend.partner.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreatePartnerCenterRequest(
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") String centerId,
        @NotNull @Valid Manager manager
) {
    public record Manager(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 50) String displayName
    ) {
    }
}
