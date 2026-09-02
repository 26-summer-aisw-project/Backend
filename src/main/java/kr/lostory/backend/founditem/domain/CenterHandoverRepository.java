package kr.lostory.backend.founditem.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CenterHandoverRepository extends JpaRepository<CenterHandover, Long> {

    Optional<CenterHandover> findByFoundItemIdAndSupersededAtIsNull(Long foundItemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select handover from CenterHandover handover where handover.id = :id")
    Optional<CenterHandover> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select handover from CenterHandover handover
            where handover.centerId = :centerId
              and handover.status = :status
              and handover.supersededAt is null
            order by handover.userConfirmedAt, handover.id
            """)
    List<CenterHandover> findCurrentByCenterAndStatus(
            @Param("centerId") Long centerId,
            @Param("status") CenterHandoverStatus status);
}
