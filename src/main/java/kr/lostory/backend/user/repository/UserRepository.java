package kr.lostory.backend.user.repository;

import java.util.Optional;

import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmail(String email);

	Optional<User> findByEmail(String email);

	Optional<User> findByIdAndStatus(Long id, UserStatus status);
}
