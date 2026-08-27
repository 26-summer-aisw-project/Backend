package kr.lostory.backend.point.presentation;

import java.time.Instant;

public record CandidateAccessResponse(
		String reportId,
		Instant unlockedAt,
		int debitedPoints,
		int remainingBalance,
		boolean replayed
) {
}
