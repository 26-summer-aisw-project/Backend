package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.GlobalExceptionHandler;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.lostreport.application.LostReportLifecycleCleanupService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import({
        PostgresTestContainerConfig.class,
        FoundItemDraftApiIntegrationTest.FakeBoundaryConfig.class,
        LostReportLifecycleCleanupIntegrationTest.FixedClockConfig.class
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LostReportLifecycleCleanupIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired LostReportLifecycleCleanupService cleanup;
    @Autowired GlobalExceptionHandler errors;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM match_candidates");
        jdbc.update("DELETE FROM report_waypoints");
        jdbc.update("DELETE FROM lost_reports");
        jdbc.update("DELETE FROM found_items");
    }

    @Test
    void scheduledBoundaryExpiresAtExactDeadlineDeletesCandidatesAndPreservesVisibleReportData() throws Exception {
        // Given
        User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task9-report.example", "hash"));
        Long itemId = item(owner.getId());
        Long reportId = report(owner.getId(), NOW, "[{\"centerId\":\"201\"}]");
        waypoint(reportId);
        candidate(reportId, itemId);

        // When
        cleanup.scheduledCleanup();

        // Then
        assertThat(LostReportLifecycleCleanupService.class.getMethod("scheduledCleanup")
                .isAnnotationPresent(Scheduled.class)).isTrue();
        assertThat(jdbc.queryForMap("""
                SELECT status, center_guidance::text AS guidance FROM lost_reports WHERE id = ?
                """, reportId))
                .containsEntry("status", "EXPIRED")
                .containsEntry("guidance", "[{\"centerId\": \"201\"}]");
        assertThat(count("report_waypoints", reportId)).isOne();
        assertThat(count("match_candidates", reportId)).isZero();

        LostoryException exception = catchThrowableOfType(
                () -> cleanup.requireOpen(reportId, owner.getId()), LostoryException.class);
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REPORT_NOT_OPEN);
        assertThat(errors.handleLostoryException(exception).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errors.handleLostoryException(exception).getBody().code()).isEqualTo("REPORT_NOT_OPEN");
    }

    @Test
    void reportBeforeDeadlineRemainsOpenWithCandidates() {
        // Given
        User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task9-report.example", "hash"));
        Long itemId = item(owner.getId());
        Long reportId = report(owner.getId(), NOW.plusMillis(1), "[]");
        candidate(reportId, itemId);

        // When
        int expired = cleanup.runCleanup();

        // Then
        assertThat(expired).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM lost_reports WHERE id = ?",
                String.class, reportId)).isEqualTo("OPEN");
        assertThat(count("match_candidates", reportId)).isOne();
        cleanup.requireOpen(reportId, owner.getId());
    }

    private Long item(Long ownerId) {
        return jdbc.queryForObject("""
                INSERT INTO found_items
                    (finder_id, name, category, description, found_at, found_location,
                     storage_method, handover_status, status, vision_status, analysis_generation,
                     created_at, updated_at, expired_at)
                VALUES (?, 'wallet', 'WALLET', 'wallet', ?,
                        ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
                        'LEFT_IN_PLACE', 'NONE', 'ACTIVE', 'READY', 1, ?, ?, ?)
                RETURNING id
                """, Long.class, ownerId, ts(NOW.minusSeconds(10)), ts(NOW.minusSeconds(10)),
                ts(NOW.minusSeconds(1)), ts(NOW.plusSeconds(3600)));
    }

    private Long report(Long ownerId, Instant expiredAt, String guidance) {
        return jdbc.queryForObject("""
                INSERT INTO lost_reports
                    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
                     effective_search_radius_meters, radius_policy_version, center_guidance,
                     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
                VALUES (?, 'WALLET', ?, ?, 'wallet', 1000, 1000, 'p0-radius-v1', ?::jsonb,
                        false, 'p0-matching-v1', 'OPEN', ?, ?, ?)
                RETURNING id
                """, Long.class, ownerId, ts(NOW.minusSeconds(10)), ts(NOW.minusSeconds(5)), guidance,
                ts(expiredAt), ts(NOW.minusSeconds(20)), ts(NOW.minusSeconds(1)));
    }

    private void waypoint(Long reportId) {
        jdbc.update("""
                INSERT INTO report_waypoints (report_id, ordinal, place_name, location, created_at)
                VALUES (?, 1, 'station',
                        ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography, ?)
                """, reportId, ts(NOW));
    }

    private void candidate(Long reportId, Long itemId) {
        jdbc.update("""
                INSERT INTO match_candidates
                    (report_id, item_id, rank, score, score_breakdown, created_at)
                VALUES (?, ?, 1, 90, '{}', ?)
                """, reportId, itemId, ts(NOW));
    }

    private int count(String table, Long reportId) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE report_id = ?",
                Integer.class, reportId);
    }

    private Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock task9ReportClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
