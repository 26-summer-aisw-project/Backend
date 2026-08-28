package kr.lostory.backend.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
	@Schema(description = "계정에 사용할 이메일 주소")
	@NotBlank @Email @Size(max = 320) String email,
	@Schema(description = "양끝을 포함해 8~72 UTF-8 바이트의 비밀번호", format = "password", extensions = {
		@Extension(properties = @ExtensionProperty(name = "x-password-byte-minimum", value = "8", parseValue = true)),
		@Extension(properties = @ExtensionProperty(name = "x-password-byte-maximum", value = "72", parseValue = true)),
		@Extension(properties = @ExtensionProperty(name = "x-password-byte-encoding", value = "UTF-8"))
	})
	@NotBlank @PasswordByteLength String password,
	@Schema(description = "프로필에 표시할 이름")
	@NotBlank @Size(max = 50) String displayName
) {
	public SignupRequest {
		if (email != null) {
			email = AuthService.normalizeEmail(email);
		}
		if (displayName != null) {
			displayName = displayName.trim();
		}
	}
}
