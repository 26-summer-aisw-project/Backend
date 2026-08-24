package kr.lostory.backend.lostreport.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReportWaypointRepository extends JpaRepository<ReportWaypoint, Long> {

	List<ReportWaypoint> findAllByReportIdOrderByOrdinalAsc(Long reportId);

	void deleteAllByReportId(Long reportId);
}
