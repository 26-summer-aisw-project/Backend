package kr.lostory.backend.founditem.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FoundItemVisionJobRepository extends JpaRepository<FoundItemVisionJob, Long> {

    long countByFoundItemId(Long foundItemId);

    @Modifying
    @Query("update FoundItemVisionJob job set job.status = 'SUPERSEDED', job.leaseOwner = null, "
            + "job.leaseUntil = null, job.completedAt = CURRENT_TIMESTAMP, job.updatedAt = CURRENT_TIMESTAMP "
            + "where job.foundItemId = :itemId "
            + "and job.status in ('PENDING', 'PROCESSING')")
    int supersedePendingByFoundItemId(@Param("itemId") Long itemId);
}
