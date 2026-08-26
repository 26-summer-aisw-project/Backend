package kr.lostory.backend.point.domain;

import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CandidateAccessRepository extends JpaRepository<CandidateAccess, Long> {

	Optional<CandidateAccess> findByReportId(Long reportId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select access from CandidateAccess access where access.id = :id")
	Optional<CandidateAccess> findByIdForUpdate(@Param("id") Long id);
}
