package kr.lostory.backend.point.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PointLedgerRepository extends JpaRepository<PointLedger, Long> {

	Optional<PointLedger> findByIdempotencyKey(UUID idempotencyKey);

	boolean existsByUserIdAndEntryType(Long userId, PointEntryType entryType);
}
