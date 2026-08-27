package kr.lostory.backend.point.application;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.point.domain.PointAccountRepository;
import kr.lostory.backend.point.domain.PointLedgerRepository;
import kr.lostory.backend.point.presentation.PointResponses;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointQueryService {

	private final PointAccountRepository accounts;
	private final PointLedgerRepository ledger;

	public PointQueryService(PointAccountRepository accounts, PointLedgerRepository ledger) {
		this.accounts = accounts;
		this.ledger = ledger;
	}

	@Transactional(readOnly = true)
	public PointResponses.Balance balance(Long requesterId) {
		return new PointResponses.Balance(accounts.findById(requesterId)
				.orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND)).getBalance());
	}

	@Transactional(readOnly = true)
	public PointResponses.LedgerList ledger(Long requesterId, int page, int pageSize) {
		var result = ledger.findByUserId(requesterId, PageRequest.of(page - 1, pageSize,
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
		return new PointResponses.LedgerList(result.stream().map(PointResponses.LedgerEntry::from).toList(),
				new PointResponses.Meta(page, pageSize, result.getTotalElements()));
	}
}
