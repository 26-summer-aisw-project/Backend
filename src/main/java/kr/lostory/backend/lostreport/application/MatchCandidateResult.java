package kr.lostory.backend.lostreport.application;

import java.math.BigDecimal;

public record MatchCandidateResult(String candidateId, short rank, BigDecimal score) {
}
