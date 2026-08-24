package kr.lostory.backend.lostreport.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record LostReportCandidateResponse(
		Instant lastMatchedAt,
		boolean candidatesStale,
		List<Candidate> data
) {
	public record Candidate(String candidateId, short rank, BigDecimal score) {
	}
}
