package kr.lostory.backend.lostreport.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LostReportLifecycleCleanupService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LostReportLifecycleCleanupService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString = "${lost-report.cleanup-interval:PT1H}",
            initialDelayString = "${lost-report.cleanup-initial-delay:PT1M}"
    )
    @Transactional
    public void scheduledCleanup() {
        expireAt(clock.instant());
    }

    @Transactional
    public int runCleanup() {
        return expireAt(clock.instant());
    }

    @Transactional
    public Instant requireOpen(Long reportId, Long requesterId) {
        Instant now = clock.instant();
        expireReportAt(reportId, now);
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM lost_reports WHERE id = ? AND reporter_id = ? FOR UPDATE",
                String.class, reportId, requesterId);
        if (statuses.isEmpty()) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!statuses.getFirst().equals("OPEN")) {
            throw new LostoryException(ErrorCode.REPORT_NOT_OPEN);
        }
        return now;
    }

    @Transactional
    public void applyExpiry(Long reportId) {
        expireReportAt(reportId, clock.instant());
    }

    private int expireAt(Instant now) {
        Timestamp boundary = Timestamp.from(now);
        List<Long> reportIds = jdbc.queryForList("""
                UPDATE lost_reports SET status = 'EXPIRED', updated_at = ?
                WHERE status = 'OPEN' AND expired_at <= ?
                RETURNING id
                """, Long.class, boundary, boundary);
        for (Long reportId : reportIds) {
            jdbc.update("DELETE FROM match_candidates WHERE report_id = ?", reportId);
        }
        return reportIds.size();
    }

    private void expireReportAt(Long reportId, Instant now) {
        Timestamp boundary = Timestamp.from(now);
        int expired = jdbc.update("""
                UPDATE lost_reports SET status = 'EXPIRED', updated_at = ?
                WHERE id = ? AND status = 'OPEN' AND expired_at <= ?
                """, boundary, reportId, boundary);
        if (expired == 1) {
            jdbc.update("DELETE FROM match_candidates WHERE report_id = ?", reportId);
        }
    }
}
