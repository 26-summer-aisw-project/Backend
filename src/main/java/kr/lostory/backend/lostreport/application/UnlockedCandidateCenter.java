package kr.lostory.backend.lostreport.application;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.CenterHandover;
import kr.lostory.backend.founditem.domain.CenterHandoverRepository;
import kr.lostory.backend.founditem.domain.CenterHandoverStatus;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.lostcenter.domain.LostCenter;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import kr.lostory.backend.lostreport.presentation.UnlockedCandidateResponse;
import org.springframework.stereotype.Service;

@Service
class UnlockedCandidateCenter {

	private final CenterHandoverRepository handovers;
	private final LostCenterRepository centers;

	UnlockedCandidateCenter(CenterHandoverRepository handovers, LostCenterRepository centers) {
		this.handovers = handovers;
		this.centers = centers;
	}

	UnlockedCandidateResponse.Center project(FoundItem item) {
		CenterHandover handover = handovers.findByFoundItemIdAndSupersededAtIsNull(item.getId()).orElse(null);
		if (handover == null || (handover.getStatus() != CenterHandoverStatus.USER_CONFIRMED
				&& handover.getStatus() != CenterHandoverStatus.CENTER_CONFIRMED)) return null;
		LostCenter center = centers.findById(handover.getCenterId())
				.orElseThrow(() -> new LostoryException(ErrorCode.INTERNAL_SERVER_ERROR));
		String notice = handover.getStatus() == CenterHandoverStatus.USER_CONFIRMED
				? "사용자 인계 확인, 센터 검증 전" : "센터 확인 완료";
		return new UnlockedCandidateResponse.Center(center.getName(), center.getContactPhone(),
				handover.getStatus().name(), notice);
	}
}
