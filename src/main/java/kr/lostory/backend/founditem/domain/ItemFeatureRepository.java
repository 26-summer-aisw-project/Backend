package kr.lostory.backend.founditem.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemFeatureRepository extends JpaRepository<ItemFeature, Long> {

	List<ItemFeature> findByItemIdAndSourceAndVisibilityOrderByKindAscOrdinalAsc(
		Long itemId, ItemFeatureSource source, ItemFeatureVisibility visibility);
}
