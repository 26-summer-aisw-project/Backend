package kr.lostory.backend.auth;

import java.time.Instant;

import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserStatus;

public record LoginResponse(
	String accessToken,
	String tokenType,
	Instant expiresAt,
	LoginUser user
) {
	public record LoginUser(String id, UserStatus status) {
		public static LoginUser from(User user) {
			return new LoginUser(user.getId().toString(), user.getStatus());
		}
	}
}
