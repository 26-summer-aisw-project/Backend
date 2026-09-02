package kr.lostory.backend.partner.presentation;

import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import kr.lostory.backend.auth.PasswordByteLength;

public record ActivatePartnerManagerRequest(
        @Schema(description = "양끝을 포함해 8~72 UTF-8 바이트의 비밀번호", format = "password", extensions = {
                @Extension(properties = @ExtensionProperty(name = "x-password-byte-minimum", value = "8", parseValue = true)),
                @Extension(properties = @ExtensionProperty(name = "x-password-byte-maximum", value = "72", parseValue = true)),
                @Extension(properties = @ExtensionProperty(name = "x-password-byte-encoding", value = "UTF-8"))
        })
        @NotBlank @PasswordByteLength String password
) {
}
