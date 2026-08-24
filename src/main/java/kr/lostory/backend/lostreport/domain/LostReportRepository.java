package kr.lostory.backend.lostreport.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LostReportRepository extends JpaRepository<LostReport, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select report from LostReport report where report.id = :reportId")
    Optional<LostReport> findByIdForUpdate(@Param("reportId") Long reportId);

    @Modifying
    @Query("update LostReport report set report.candidatesStale = true where report.status = 'OPEN' and report.candidatesStale = false")
    int markOpenCandidatesStale();

    Page<LostReport> findByReporterId(Long reporterId, Pageable pageable);

    Page<LostReport> findByReporterIdAndStatus(Long reporterId, LostReportStatus status, Pageable pageable);
}
