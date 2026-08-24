package kr.lostory.backend.founditem.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FoundItemVisionJobRepository extends JpaRepository<FoundItemVisionJob, Long> {

    long countByFoundItemId(Long foundItemId);
}
