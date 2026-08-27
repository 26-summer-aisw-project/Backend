package kr.lostory.backend.returnrecord.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnRecordRepository extends JpaRepository<ReturnRecord, Long> {

    boolean existsByFoundItemIdAndLostReportId(Long foundItemId, Long lostReportId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select record from ReturnRecord record
            where record.handoverId = :handoverId
               or record.foundItemId = :itemId
               or record.lostReportId = :reportId
            order by record.id
            """)
    List<ReturnRecord> findCollisionsForUpdate(
            @Param("handoverId") Long handoverId,
            @Param("itemId") Long itemId,
            @Param("reportId") Long reportId);
}
