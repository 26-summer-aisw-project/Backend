package kr.lostory.backend.user.api;

import java.util.List;

import kr.lostory.backend.user.domain.User;

public record UserResponse(Long id, String email, List<String> roles) {

	public static UserResponse from(User user) {
		return new UserResponse(
			user.getId(),
			user.getEmail(),
			user.getRoles().stream().map(Enum::name).sorted().toList());
	}
}
