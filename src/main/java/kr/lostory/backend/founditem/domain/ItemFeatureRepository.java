package kr.lostory.backend.founditem.domain;

import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemFeatureRepository extends JpaRepository<ItemFeature, Long> {

	List<ItemFeature> findByItemIdAndSourceAndVisibilityOrderByKindAscOrdinalAsc(
		Long itemId, ItemFeatureSource source, ItemFeatureVisibility visibility);

    List<ItemFeature> findByItemIdAndKindAndSourceAndVisibilityOrderByOrdinalAscIdAsc(
            Long itemId,
            ItemFeatureKind kind,
            ItemFeatureSource source,
            ItemFeatureVisibility visibility);

    @Modifying
    @Query("delete from ItemFeature feature where feature.itemId = :itemId and feature.source = :source "
            + "and feature.kind in :kinds")
    void deleteByItemIdAndSourceAndKinds(
            @Param("itemId") Long itemId,
            @Param("source") ItemFeatureSource source,
            @Param("kinds") List<ItemFeatureKind> kinds);

    @Modifying
    @Query("delete from ItemFeature feature where feature.itemId = :itemId and feature.source = :source")
    void deleteByItemIdAndSource(
            @Param("itemId") Long itemId,
            @Param("source") ItemFeatureSource source);
}
