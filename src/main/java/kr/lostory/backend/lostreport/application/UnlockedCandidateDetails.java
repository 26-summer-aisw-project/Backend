package kr.lostory.backend.lostreport.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;
import kr.lostory.backend.founditem.domain.ItemFeatureRepository;
import kr.lostory.backend.founditem.domain.ItemFeatureSource;
import kr.lostory.backend.founditem.domain.ItemFeatureVisibility;
import kr.lostory.backend.lostreport.presentation.UnlockedCandidateResponse;
import org.springframework.stereotype.Service;

@Service
class UnlockedCandidateDetails {

	private final ItemFeatureRepository features;
	private final UnlockedCandidateThumbnail thumbnail;
	private final UnlockedCandidateCenter center;

	UnlockedCandidateDetails(ItemFeatureRepository features, UnlockedCandidateThumbnail thumbnail,
			UnlockedCandidateCenter center) {
		this.features = features;
		this.thumbnail = thumbnail;
		this.center = center;
	}

	UnlockedCandidateResponse.PublicFeatures publicFeatures(Long itemId) {
		Map<ItemFeatureKind, String> values = new EnumMap<>(ItemFeatureKind.class);
		for (ItemFeatureKind kind : List.of(ItemFeatureKind.COLOR, ItemFeatureKind.PUBLIC_DESCRIPTION)) {
			features.findByItemIdAndKindAndSourceAndVisibilityOrderByOrdinalAscIdAsc(itemId, kind,
					ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW).stream().findFirst()
					.ifPresent(feature -> values.put(kind, feature.getFeatureValue()));
		}
		return new UnlockedCandidateResponse.PublicFeatures(values.get(ItemFeatureKind.COLOR),
				values.get(ItemFeatureKind.PUBLIC_DESCRIPTION));
	}

	String thumbnail(Long itemId) {
		return thumbnail.sign(itemId);
	}

	UnlockedCandidateResponse.Center center(FoundItem item) {
		return center.project(item);
	}
}
