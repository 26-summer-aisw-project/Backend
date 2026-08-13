package kr.lostory.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
	@NotBlank @Email @Size(max = 320) String email,
	@NotBlank @PasswordByteLength String password,
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
