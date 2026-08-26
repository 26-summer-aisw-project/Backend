package kr.lostory.backend.auth;

import java.util.Locale;
import java.util.UUID;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.point.domain.PointService;
import kr.lostory.backend.user.api.UserResponse;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserStatus;
import kr.lostory.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final JwtTokenService tokenService;
	private final PointService pointService;

	@Transactional
	public UserResponse signup(SignupRequest request) {
		String email = normalizeEmail(request.email());
		if (userRepository.existsByEmail(email)) {
			throw new LostoryException(ErrorCode.DUPLICATE_EMAIL);
		}
		User user;
		try {
			user = userRepository.saveAndFlush(
					new User(email, passwordEncoder.encode(request.password()), request.displayName())
			);
		} catch (DataIntegrityViolationException exception) {
			throw new LostoryException(ErrorCode.DUPLICATE_EMAIL);
		}
		pointService.grantSignup(user.getId(), UUID.randomUUID());
		return UserResponse.from(user);
	}

	@Transactional(readOnly = true)
	public LoginResponse login(AuthRequest request) {
		User user = userRepository.findByEmail(normalizeEmail(request.email()))
			.filter(candidate -> candidate.getStatus() == UserStatus.ACTIVE)
			.filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
			.orElseThrow(() -> new LostoryException(ErrorCode.INVALID_CREDENTIALS));
		JwtTokenService.IssuedToken token = tokenService.issue(user);
		return new LoginResponse(token.value(), "Bearer", token.expiresAt(), LoginResponse.LoginUser.from(user));
	}

	@Transactional(readOnly = true)
	public UserResponse currentUser(String subject) {
		try {
			return userRepository.findByIdAndStatus(Long.valueOf(subject), UserStatus.ACTIVE)
				.map(UserResponse::from)
				.orElseThrow(() -> new LostoryException(ErrorCode.INVALID_TOKEN));
		} catch (NumberFormatException exception) {
			throw new LostoryException(ErrorCode.INVALID_TOKEN);
		}
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}
}
