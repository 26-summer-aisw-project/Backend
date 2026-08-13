package kr.lostory.backend.founditem.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FoundItemImageRepository extends JpaRepository<FoundItemImage, Long> {

    long countByFoundItemId(Long foundItemId);

    List<FoundItemImage> findAllByFoundItemIdOrderByCreatedAtAsc(Long foundItemId);
}