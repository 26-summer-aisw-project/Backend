package kr.lostory.backend;

import java.util.UUID;

import kr.lostory.backend.point.domain.PointAccount;
import kr.lostory.backend.point.domain.PointAccountRepository;
import kr.lostory.backend.point.domain.PointEntryType;
import kr.lostory.backend.point.domain.PointLedger;
import kr.lostory.backend.point.domain.PointLedgerRepository;
import kr.lostory.backend.point.domain.PointService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest
class PointFoundationIntegrationTest {

	@Autowired
	private PointService pointService;

	@Autowired
	private PointAccountRepository accountRepository;

	@Autowired
	private PointLedgerRepository ledgerRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void blockedAndDeletedFindersCanReceiveReturnRewardWithoutReactivation() {
		// Given
		User blocked = saveUser("blocked");
		User deleted = saveUser("deleted");
		jdbcTemplate.update("UPDATE users SET status = 'BLOCKED' WHERE id = ?", blocked.getId());
		jdbcTemplate.update("UPDATE users SET status = 'DELETED' WHERE id = ?", deleted.getId());

		// When
		pointService.rewardCenterConfirmedReturn(blocked.getId(), 7001L, UUID.randomUUID());
		pointService.rewardCenterConfirmedReturn(deleted.getId(), 7002L, UUID.randomUUID());

		// Then
		assertThat(accountRepository.findById(blocked.getId()).orElseThrow().getBalance()).isEqualTo(5);
		assertThat(accountRepository.findById(deleted.getId()).orElseThrow().getBalance()).isEqualTo(5);
		assertThat(jdbcTemplate.queryForList(
			"SELECT status FROM users WHERE id IN (?, ?) ORDER BY id",
			String.class,
			blocked.getId(), deleted.getId()
		)).containsExactly("BLOCKED", "DELETED");
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM point_ledger WHERE user_id IN (?, ?) "
				+ "AND entry_type = 'CENTER_RETURN_REWARD' AND amount = 5",
			Integer.class,
			blocked.getId(), deleted.getId()
		)).isEqualTo(2);
	}

	@Test
	void duplicateLedgerKeyRollsBackNewRewardAccount() {
		// Given
		User existing = saveUser("existing");
		User rewardTarget = saveUser("rollback");
		UUID duplicateKey = UUID.randomUUID();
		accountRepository.saveAndFlush(new PointAccount(existing.getId()));
		ledgerRepository.saveAndFlush(new PointLedger(
			existing.getId(), PointEntryType.DEMO_GRANT, 1, duplicateKey, null
		));

		// When / Then
		assertThatThrownBy(() -> pointService.rewardCenterConfirmedReturn(rewardTarget.getId(), 7101L, duplicateKey))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(accountRepository.findById(rewardTarget.getId())).isEmpty();
		assertThat(jdbcTemplate.queryForObject(
			"SELECT count(*) FROM point_ledger WHERE user_id = ?",
			Integer.class,
			rewardTarget.getId()
		)).isZero();
	}

	@Test
	void negativeBalanceGuardRollsBackCandidateDebitLedger() {
		// Given
		User user = saveUser("negative");
		UUID debitKey = UUID.randomUUID();
		accountRepository.saveAndFlush(new PointAccount(user.getId()));

		// When / Then
		assertThatThrownBy(() -> pointService.debitCandidateAccess(user.getId(), 7201L, debitKey))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(accountRepository.findById(user.getId()).orElseThrow().getBalance()).isZero();
		assertThat(ledgerRepository.findByIdempotencyKey(debitKey)).isEmpty();
	}

	private User saveUser(String prefix) {
		return userRepository.saveAndFlush(new User(prefix + "-" + UUID.randomUUID() + "@example.test", "hash"));
	}
}
