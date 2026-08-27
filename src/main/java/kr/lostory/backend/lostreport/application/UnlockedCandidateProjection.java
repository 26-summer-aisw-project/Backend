package kr.lostory.backend.lostreport.application;

import java.time.ZoneOffset;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.lostreport.presentation.LostReportCandidateResponse;
import kr.lostory.backend.lostreport.presentation.UnlockedCandidateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class UnlockedCandidateProjection {

	private final FoundItemRepository items;
	private final UnlockedCandidateDetails details;

	UnlockedCandidateProjection(FoundItemRepository items, UnlockedCandidateDetails details) {
		this.items = items;
		this.details = details;
	}

	@Transactional(readOnly = true)
	public UnlockedCandidateResponse.Candidate candidate(LostReportCandidateResponse.Candidate source) {
		Long itemId = Long.valueOf(source.candidateId());
		FoundItem item = items.findById(itemId)
				.orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
		return new UnlockedCandidateResponse.Candidate(source.candidateId(), source.rank(), source.score(),
				item.getCategory(), item.getFoundAt().atZone(ZoneOffset.UTC).toLocalDate(),
				details.thumbnail(itemId), details.publicFeatures(itemId), details.center(item));
	}
}
