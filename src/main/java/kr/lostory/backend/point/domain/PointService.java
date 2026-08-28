package kr.lostory.backend.point.domain;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PointService {

	private final PointAccountRepository accountRepository;
	private final PointLedgerRepository ledgerRepository;
	private final PointPolicy policy;

	@Transactional
	public void grantSignup(Long userId, UUID idempotencyKey) {
		apply(userId, PointLedger.signupGrant(userId, idempotencyKey, policy.signupGrant()));
	}

	@Transactional
	public void debitCandidateAccess(Long userId, Long reportId, UUID idempotencyKey) {
		PointAccount account = accountRepository.findByUserIdForUpdate(userId).orElseThrow();
		apply(account, PointLedger.candidateAccessDebit(
			userId, reportId, idempotencyKey, policy.candidateAccessCost()));
	}

	@Transactional
	public void rewardCenterConfirmedReturn(Long userId, Long returnId, UUID idempotencyKey) {
		PointAccount account = accountRepository.findByUserIdForUpdate(userId)
			.orElseGet(() -> accountRepository.saveAndFlush(new PointAccount(userId)));
		apply(account, PointLedger.centerReturnReward(
			userId, returnId, idempotencyKey, policy.centerConfirmedReturnReward()));
	}

	private void apply(Long userId, PointLedger ledger) {
		PointAccount account = accountRepository.saveAndFlush(new PointAccount(userId));
		apply(account, ledger);
	}

	private void apply(PointAccount account, PointLedger ledger) {
		ledgerRepository.save(ledger);
		account.apply(ledger);
		accountRepository.flush();
		ledgerRepository.flush();
	}
}
