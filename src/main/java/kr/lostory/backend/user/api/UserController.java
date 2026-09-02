package kr.lostory.backend.user.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.lostory.backend.auth.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "사용자", description = "현재 인증 사용자 프로필 API")
public class UserController {

	private final AuthService authService;

	@GetMapping("/me")
	@SecurityRequirement(name = "bearerAuth")
	@Operation(summary = "내 프로필 조회", description = "Bearer JWT로 인증된 현재 사용자의 안전한 프로필을 반환합니다.")
	public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
		return authService.currentUser(jwt.getSubject());
	}
}
