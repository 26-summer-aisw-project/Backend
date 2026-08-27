package kr.lostory.backend.point.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointLedgerRepository extends JpaRepository<PointLedger, Long> {

	Optional<PointLedger> findByIdempotencyKey(UUID idempotencyKey);

	boolean existsByUserIdAndEntryType(Long userId, PointEntryType entryType);

	Page<PointLedger> findByUserId(Long userId, Pageable pageable);
}
