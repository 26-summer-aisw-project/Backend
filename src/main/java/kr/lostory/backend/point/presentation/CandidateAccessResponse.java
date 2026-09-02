package kr.lostory.backend.point.presentation;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

public record CandidateAccessResponse(
		String reportId,
		Instant unlockedAt,
		int debitedPoints,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
				description = "최초 차감 후 잔액. 스냅샷 도입 전 열람은 null일 수 있습니다.") Integer remainingBalance,
		boolean replayed
) {
}
