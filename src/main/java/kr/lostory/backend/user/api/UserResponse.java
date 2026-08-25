package kr.lostory.backend.user.api;

import java.util.List;

import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserStatus;

public record UserResponse(String id, String email, String displayName, UserStatus status, List<String> roles) {

	public static UserResponse from(User user) {
		return new UserResponse(
			user.getId().toString(),
			user.getEmail(),
			user.getDisplayName(),
			user.getStatus(),
			user.getRoles().stream().map(Enum::name).sorted().toList());
	}
}
