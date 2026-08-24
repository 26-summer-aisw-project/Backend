package kr.lostory.backend.lostreport.application;

import java.math.BigDecimal;

public record MatchScore(
		BigDecimal score,
		BigDecimal route,
		BigDecimal time,
		BigDecimal category,
		BigDecimal color,
		BigDecimal description
) {
}
