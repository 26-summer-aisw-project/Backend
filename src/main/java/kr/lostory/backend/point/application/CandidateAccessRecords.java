package kr.lostory.backend.point.application;

import java.util.UUID;
import kr.lostory.backend.point.domain.CandidateAccess;
import kr.lostory.backend.point.domain.CandidateAccessIdempotencyReceipt;
import kr.lostory.backend.point.domain.CandidateAccessIdempotencyReceiptRepository;
import kr.lostory.backend.point.domain.PointLedger;
import kr.lostory.backend.point.domain.PointLedgerRepository;
import org.springframework.stereotype.Component;

@Component
class CandidateAccessRecords {

	private final CandidateAccessIdempotencyReceiptRepository receipts;
	private final PointLedgerRepository ledger;

	CandidateAccessRecords(CandidateAccessIdempotencyReceiptRepository receipts, PointLedgerRepository ledger) {
		this.receipts = receipts;
		this.ledger = ledger;
	}

	CandidateAccessIdempotencyReceipt receipt(UUID key) {
		return receipts.findById(key).orElse(null);
	}

	void lock(UUID key) {
		receipts.lockByIdempotencyKey(key);
	}

	boolean ledgerKeyExists(UUID key) {
		return ledger.findByIdempotencyKey(key).isPresent();
	}

	PointLedger debit(Long userId, Long reportId, UUID key, int cost) {
		return ledger.saveAndFlush(PointLedger.candidateAccessDebit(userId, reportId, key, cost));
	}

	PointLedger linkedDebit(CandidateAccess access) {
		return ledger.findById(access.getDebitTransactionId()).orElseThrow();
	}

	void saveReceipt(UUID key, CandidateAccess access) {
		receipts.saveAndFlush(new CandidateAccessIdempotencyReceipt(
				key, access.getUserId(), access.getReportId(), access.getId()));
	}
}
