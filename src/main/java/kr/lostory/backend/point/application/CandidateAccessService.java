package kr.lostory.backend.point.application;

import java.time.Instant;
import java.util.UUID;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.lostreport.application.LostReportLifecycleCleanupService;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportStatus;
import kr.lostory.backend.point.domain.CandidateAccess;
import kr.lostory.backend.point.domain.CandidateAccessIdempotencyReceipt;
import kr.lostory.backend.point.domain.PointAccount;
import kr.lostory.backend.point.domain.PointLedger;
import kr.lostory.backend.point.domain.PointPolicy;
import kr.lostory.backend.point.presentation.CandidateAccessResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidateAccessService {

	private final CandidateAccessLocks locks;
	private final CandidateAccessRecords records;
	private final LostReportLifecycleCleanupService lifecycle;

	public CandidateAccessService(CandidateAccessLocks locks, CandidateAccessRecords records,
			LostReportLifecycleCleanupService lifecycle) {
		this.locks = locks;
		this.records = records;
		this.lifecycle = lifecycle;
	}

	@Transactional
	public CandidateAccessResponse unlock(Long reportId, Long requesterId, UUID idempotencyKey) {
		records.lock(idempotencyKey);
		LostReport report = locks.report(reportId);
		if (!report.getReporterId().equals(requesterId)) {
			throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		Instant databaseNow = lifecycle.databaseNow();
		if (report.getStatus() != LostReportStatus.OPEN || !report.getExpiredAt().isAfter(databaseNow)) {
			lifecycle.expireLockedReport(reportId, databaseNow);
			throw new LostoryException(ErrorCode.REPORT_NOT_OPEN);
		}

		CandidateAccess access = locks.access(reportId);
		PointAccount account = locks.account(requesterId);
		CandidateAccessIdempotencyReceipt receipt = records.receipt(idempotencyKey);
		if (receipt != null) {
			if (!receipt.getUserId().equals(requesterId) || !receipt.getReportId().equals(reportId)) {
				throw new LostoryException(ErrorCode.POINT_IDEMPOTENCY_CONFLICT);
			}
			CandidateAccess replayed = locks.accessById(receipt.getCandidateAccessId());
			return response(replayed, account, true);
		}
		if (records.ledgerKeyExists(idempotencyKey)) {
			throw new LostoryException(ErrorCode.POINT_IDEMPOTENCY_CONFLICT);
		}
		if (access != null) {
			records.saveReceipt(idempotencyKey, access);
			return response(access, account, true);
		}
		if (!account.canDebit(PointPolicy.CANDIDATE_ACCESS_COST)) {
			throw new LostoryException(ErrorCode.INSUFFICIENT_POINTS);
		}
		PointLedger debit = records.debit(requesterId, reportId, idempotencyKey);
		locks.apply(account, debit);
		CandidateAccess created = locks.create(reportId, requesterId, debit);
		records.saveReceipt(idempotencyKey, created);
		return response(created, account, false);
	}

	private CandidateAccessResponse response(CandidateAccess access, PointAccount account, boolean replayed) {
		return new CandidateAccessResponse(access.getReportId().toString(), access.getUnlockedAt(),
				PointPolicy.CANDIDATE_ACCESS_COST, account.getBalance(), replayed);
	}
}
