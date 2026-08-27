package kr.lostory.backend.founditem.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CenterHandoverRepository extends JpaRepository<CenterHandover, Long> {

    Optional<CenterHandover> findByFoundItemIdAndSupersededAtIsNull(Long foundItemId);

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
