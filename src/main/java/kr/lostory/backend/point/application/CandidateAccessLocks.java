package kr.lostory.backend.point.application;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import kr.lostory.backend.point.domain.CandidateAccess;
import kr.lostory.backend.point.domain.CandidateAccessRepository;
import kr.lostory.backend.point.domain.PointAccount;
import kr.lostory.backend.point.domain.PointAccountRepository;
import kr.lostory.backend.point.domain.PointLedger;
import org.springframework.stereotype.Component;

@Component
class CandidateAccessLocks {

	private final LostReportRepository reports;
	private final CandidateAccessRepository accesses;
	private final PointAccountRepository accounts;

	CandidateAccessLocks(LostReportRepository reports, CandidateAccessRepository accesses,
			PointAccountRepository accounts) {
		this.reports = reports;
		this.accesses = accesses;
		this.accounts = accounts;
	}

	LostReport report(Long reportId) {
		return reports.findByIdForUpdate(reportId)
				.orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
	}

	CandidateAccess access(Long reportId) {
		return accesses.findByReportIdForUpdate(reportId).orElse(null);
	}

	CandidateAccess accessById(Long accessId) {
		return accesses.findById(accessId)
				.orElseThrow(() -> new LostoryException(ErrorCode.INTERNAL_SERVER_ERROR));
	}

	PointAccount account(Long userId) {
		return accounts.findByUserIdForUpdate(userId)
				.orElseThrow(() -> new LostoryException(ErrorCode.INSUFFICIENT_POINTS));
	}

	void apply(PointAccount account, PointLedger debit) {
		account.apply(debit);
		accounts.flush();
	}

	CandidateAccess create(Long reportId, Long userId, PointLedger debit) {
		return accesses.saveAndFlush(new CandidateAccess(reportId, userId, debit.getId()));
	}
}
