package kr.lostory.backend.lostreport.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MatchCandidateRepository extends JpaRepository<MatchCandidate, Long> {

	boolean existsByReportIdAndItemId(Long reportId, Long itemId);

	@Modifying
	@Query("delete from MatchCandidate candidate where candidate.reportId = :reportId")
	void deleteAllByReportId(@Param("reportId") Long reportId);

	List<MatchCandidate> findAllByReportIdOrderByRankAsc(Long reportId);
}
