package kr.lostory.backend.founditem.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.config.VisionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VisionDailyAdmissionService {

    private final JdbcTemplate jdbc;
    private final VisionProperties properties;
    private final Clock clock;

    public VisionDailyAdmissionService(JdbcTemplate jdbc, VisionProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Admission reserve() {
        LocalDate date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Integer> counts = jdbc.queryForList("""
                INSERT INTO vision_daily_admissions (admission_date, reserved_count)
                VALUES (?, 1)
                ON CONFLICT (admission_date) DO UPDATE
                SET reserved_count = vision_daily_admissions.reserved_count + 1,
                    updated_at = NOW()
                WHERE vision_daily_admissions.reserved_count < ?
                RETURNING reserved_count
                """, Integer.class, date, properties.dailyJobLimit());
        if (counts.isEmpty()) {
            throw new LostoryException(ErrorCode.VISION_CAPACITY_EXCEEDED);
        }
        return new Admission(date);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Admission admission) {
        jdbc.update("""
                UPDATE vision_daily_admissions
                SET reserved_count = reserved_count - 1,
                    updated_at = NOW()
                WHERE admission_date = ? AND reserved_count > 0
                """, admission.date());
    }

    public record Admission(LocalDate date) {
    }
}
