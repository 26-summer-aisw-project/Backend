package kr.lostory.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AuthRequest(
	@NotBlank @Email @Size(max = 320) String email,
	@NotBlank @PasswordByteLength String password
) {
	public AuthRequest {
		if (email != null) {
			email = AuthService.normalizeEmail(email);
		}
	}
}
