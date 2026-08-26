package kr.lostory.backend.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
	@Schema(description = "로그인에 사용할 이메일 주소")
	@NotBlank @Email @Size(max = 320) String email,
	@Schema(description = "8~72 UTF-8 바이트의 비밀번호", format = "password", minLength = 8, maxLength = 72)
	@NotBlank @PasswordByteLength String password
) {
	public AuthRequest {
		if (email != null) {
			email = AuthService.normalizeEmail(email);
		}
	}
}
