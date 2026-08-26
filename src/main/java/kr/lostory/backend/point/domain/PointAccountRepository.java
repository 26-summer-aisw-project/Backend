package kr.lostory.backend.point.domain;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PointAccountRepository extends JpaRepository<PointAccount, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select account from PointAccount account where account.userId = :userId")
	Optional<PointAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
