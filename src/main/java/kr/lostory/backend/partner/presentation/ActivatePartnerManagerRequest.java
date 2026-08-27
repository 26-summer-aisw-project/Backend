package kr.lostory.backend.partner.presentation;

import jakarta.validation.constraints.NotBlank;
import kr.lostory.backend.auth.PasswordByteLength;

public record ActivatePartnerManagerRequest(
        @NotBlank @PasswordByteLength String password
) {
}
