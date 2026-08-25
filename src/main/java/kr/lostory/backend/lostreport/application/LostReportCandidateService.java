package kr.lostory.backend.lostreport.application;

import java.time.Instant;
import java.util.List;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import kr.lostory.backend.lostreport.domain.LostReportStatus;
import kr.lostory.backend.lostreport.domain.MatchCandidate;
import kr.lostory.backend.lostreport.domain.MatchCandidateRepository;
import kr.lostory.backend.lostreport.presentation.LostReportCandidateResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LostReportCandidateService {

	private final LostReportRepository reportRepository;
	private final MatchCandidateRepository candidateRepository;
	private final LostReportMatchingService matchingService;
	private final LostReportLifecycleCleanupService lifecycle;

	public LostReportCandidateService(
			LostReportRepository reportRepository,
			MatchCandidateRepository candidateRepository,
			LostReportMatchingService matchingService,
			LostReportLifecycleCleanupService lifecycle
	) {
		this.reportRepository = reportRepository;
		this.candidateRepository = candidateRepository;
		this.matchingService = matchingService;
		this.lifecycle = lifecycle;
	}

	@Transactional
	public LostReportCandidateResponse candidates(Long reportId, Long requesterId) {
		LostReport report = reportRepository.findByIdForUpdate(reportId)
				.orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
		if (!report.getReporterId().equals(requesterId)) {
			throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		Instant databaseNow = lifecycle.databaseNow();
		if (lifecycle.expireCandidateItems(reportId, databaseNow) > 0) {
			report.markCandidatesStale(databaseNow);
		}
		if (report.getStatus() != LostReportStatus.OPEN || !report.getExpiredAt().isAfter(databaseNow)) {
			lifecycle.expireLockedReport(reportId, databaseNow);
			throw new LostoryException(ErrorCode.REPORT_NOT_OPEN);
		}
		if (report.isCandidatesStale() || report.getLastMatchedAt() == null
				|| !LostReportMatchingService.POLICY_VERSION.equals(report.getMatchingPolicyVersion())) {
			matchingService.recompute(reportId);
		}
		List<LostReportCandidateResponse.Candidate> candidates = candidateRepository
				.findAllByReportIdOrderByRankAsc(reportId).stream()
				.map(this::response)
				.toList();
		return new LostReportCandidateResponse(report.getLastMatchedAt(), report.isCandidatesStale(), candidates);
	}

	private LostReportCandidateResponse.Candidate response(MatchCandidate candidate) {
		return new LostReportCandidateResponse.Candidate(
				candidate.getItemId().toString(), candidate.getRank(), candidate.getScore());
	}
}
