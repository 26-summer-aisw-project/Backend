package kr.lostory.backend.auth;

import java.time.Instant;

import kr.lostory.backend.user.api.UserResponse;

public record LoginResponse(
	String accessToken,
	String tokenType,
	Instant expiresAt,
	UserResponse user
) {
}
