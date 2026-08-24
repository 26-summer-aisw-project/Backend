package kr.lostory.backend.founditem.application;

import java.util.Optional;
import kr.lostory.backend.founditem.domain.ItemFeature;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;
import kr.lostory.backend.founditem.domain.ItemFeatureRepository;
import kr.lostory.backend.founditem.domain.ItemFeatureSource;
import kr.lostory.backend.founditem.domain.ItemFeatureVisibility;
import org.springframework.stereotype.Component;

@Component
public class MatchingFeatureResolver {

    private final ItemFeatureRepository repository;

    public MatchingFeatureResolver(ItemFeatureRepository repository) {
        this.repository = repository;
    }

    public Optional<String> resolve(Long itemId, ItemFeatureKind kind) {
        Optional<String> finder = first(itemId, kind,
                ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW);
        return finder.isPresent()
                ? finder
                : first(itemId, kind, ItemFeatureSource.AI, ItemFeatureVisibility.MATCH_ONLY);
    }

    public MatchingFeatures resolveForMatching(Long itemId) {
        Optional<String> color = first(itemId, ItemFeatureKind.COLOR,
                ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW);
        Optional<String> description = first(itemId, ItemFeatureKind.PUBLIC_DESCRIPTION,
                ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW);
        return new MatchingFeatures(
                color.isPresent() ? color : first(itemId, ItemFeatureKind.COLOR,
                        ItemFeatureSource.AI, ItemFeatureVisibility.MATCH_ONLY),
                description.isPresent() ? description : first(itemId, ItemFeatureKind.LABEL,
                        ItemFeatureSource.AI, ItemFeatureVisibility.MATCH_ONLY)
        );
    }

    public record MatchingFeatures(Optional<String> color, Optional<String> description) {
    }

    private Optional<String> first(
            Long itemId,
            ItemFeatureKind kind,
            ItemFeatureSource source,
            ItemFeatureVisibility visibility
    ) {
        return repository.findByItemIdAndKindAndSourceAndVisibilityOrderByOrdinalAscIdAsc(
                        itemId, kind, source, visibility).stream()
                .map(ItemFeature::getFeatureValue)
                .findFirst();
    }
}
