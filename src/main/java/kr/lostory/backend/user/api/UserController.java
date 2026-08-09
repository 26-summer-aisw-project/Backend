package kr.lostory.backend.user.api;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import kr.lostory.backend.auth.AuthService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final AuthService authService;

	public UserController(AuthService authService) {
		this.authService = authService;
	}

	@GetMapping("/me")
	@SecurityRequirement(name = "bearerAuth")
	public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return authService.currentUser(jwt.getSubject());
	}
}
