package kr.lostory.backend.point.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateAccessIdempotencyReceiptRepository
	extends JpaRepository<CandidateAccessIdempotencyReceipt, UUID> {

	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:key AS text), 0))", nativeQuery = true)
	void lockByIdempotencyKey(@Param("key") UUID key);
}
