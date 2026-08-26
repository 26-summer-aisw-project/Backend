package kr.lostory.backend.point.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CandidateAccessIdempotencyReceiptRepository
	extends JpaRepository<CandidateAccessIdempotencyReceipt, UUID> {
}
