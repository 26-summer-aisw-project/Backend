package kr.lostory.backend.lostreport.application;

import java.util.List;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import kr.lostory.backend.lostreport.presentation.LostReportCandidateResponse;
import kr.lostory.backend.lostreport.presentation.UnlockedCandidateResponse;
import kr.lostory.backend.point.domain.CandidateAccessRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnlockedCandidateService {

	private final Authorization authorization;
	private final UnlockedCandidateProjection projection;
	private final LostReportCandidateService candidates;

	public UnlockedCandidateService(Authorization authorization, UnlockedCandidateProjection projection,
			LostReportCandidateService candidates) {
		this.authorization = authorization;
		this.projection = projection;
		this.candidates = candidates;
	}

	public UnlockedCandidateResponse list(Long reportId, Long requesterId) {
		authorization.requireAccess(reportId, requesterId);
		List<LostReportCandidateResponse.Candidate> refreshed = candidates.candidates(reportId, requesterId).data();
		return new UnlockedCandidateResponse(refreshed.stream().map(projection::candidate).toList());
	}

	@Service
	static class Authorization {
		private final LostReportRepository reports;
		private final CandidateAccessRepository accesses;

		Authorization(LostReportRepository reports, CandidateAccessRepository accesses) {
			this.reports = reports;
			this.accesses = accesses;
		}

		@Transactional(readOnly = true)
		public void requireAccess(Long reportId, Long requesterId) {
			LostReport report = reports.findById(reportId)
					.orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
			if (!report.getReporterId().equals(requesterId)
					|| accesses.findByReportId(reportId).filter(access -> access.getUserId().equals(requesterId)).isEmpty()) {
				throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
			}
		}
	}
}
