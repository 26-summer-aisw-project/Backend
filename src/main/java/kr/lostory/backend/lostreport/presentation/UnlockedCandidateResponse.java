package kr.lostory.backend.lostreport.presentation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record UnlockedCandidateResponse(List<Candidate> data) {

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Candidate(
			String candidateId,
			short rank,
			BigDecimal score,
			String category,
			LocalDate foundDate,
			String thumbnailUrl,
			PublicFeatures publicFeatures,
			Center center
	) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PublicFeatures(String color, String publicDescription) {
	}

	public record Center(String name, String contactPhone, String handoverStatus, String notice) {
	}
}
