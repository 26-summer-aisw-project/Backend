package kr.lostory.backend.lostreport.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LostReportRepository extends JpaRepository<LostReport, Long> {

    @Modifying
    @Query("update LostReport report set report.candidatesStale = true where report.status = 'OPEN'")
    int markOpenCandidatesStale();
}
