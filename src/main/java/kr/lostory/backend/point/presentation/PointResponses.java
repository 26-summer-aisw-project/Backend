package kr.lostory.backend.point.presentation;

import java.time.Instant;
import java.util.List;
import kr.lostory.backend.point.domain.PointLedger;

public final class PointResponses {

	private PointResponses() {
	}

	public record Balance(int balance) {
	}

	public record LedgerList(List<LedgerEntry> data, Meta meta) {
	}

	public record LedgerEntry(
			String id,
			String type,
			int amount,
			String referenceType,
			String referenceId,
			Instant createdAt
	) {
		public static LedgerEntry from(PointLedger entry) {
			return new LedgerEntry(entry.getId().toString(), entry.getEntryType().name(), entry.getAmount(),
					entry.getReferenceType() == null ? null : entry.getReferenceType().name(),
					entry.getReferenceId() == null ? null : entry.getReferenceId().toString(), entry.getCreatedAt());
		}
	}

	public record Meta(int page, int pageSize, long totalItems) {
	}
}
