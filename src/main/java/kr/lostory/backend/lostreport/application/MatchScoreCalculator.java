package kr.lostory.backend.lostreport.application;

import static java.math.MathContext.DECIMAL64;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class MatchScoreCalculator {

	private static final BigDecimal ZERO = BigDecimal.ZERO;
	private static final BigDecimal ONE = BigDecimal.ONE;
	private static final BigDecimal ROUTE_WEIGHT = new BigDecimal(".35");
	private static final BigDecimal TIME_WEIGHT = new BigDecimal(".20");
	private static final BigDecimal CATEGORY_WEIGHT = new BigDecimal(".20");
	private static final BigDecimal COLOR_WEIGHT = new BigDecimal(".15");
	private static final BigDecimal DESCRIPTION_WEIGHT = new BigDecimal(".10");
	private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
	private static final Set<String> PALETTE = Set.of(
			"BLACK", "WHITE", "GRAY", "BROWN", "RED", "ORANGE", "YELLOW", "GREEN",
			"BLUE", "PURPLE", "PINK", "BEIGE", "SILVER", "GOLD", "OTHER");

	public MatchScore calculate(MatchInputs input) {
		BigDecimal route = clamp(ONE.subtract(input.distanceMeters()
				.divide(BigDecimal.valueOf(input.radiusMeters()), DECIMAL64), DECIMAL64));
		BigDecimal time = time(input.foundAt(), input.lostAtFrom(), input.lostAtTo(), input.timeWindow());
		BigDecimal category = input.reportCategory().equals(input.itemCategory()) ? ONE : ZERO;
		BigDecimal color = color(input.reportDescription(), input.color());
		BigDecimal description = input.description() == null
				? ZERO : jaccard(input.reportDescription(), input.description());
		BigDecimal weighted = route.multiply(ROUTE_WEIGHT, DECIMAL64)
				.add(time.multiply(TIME_WEIGHT, DECIMAL64), DECIMAL64)
				.add(category.multiply(CATEGORY_WEIGHT, DECIMAL64), DECIMAL64)
				.add(color.multiply(COLOR_WEIGHT, DECIMAL64), DECIMAL64)
				.add(description.multiply(DESCRIPTION_WEIGHT, DECIMAL64), DECIMAL64);
		return new MatchScore(weighted.multiply(BigDecimal.valueOf(100), DECIMAL64)
				.setScale(2, RoundingMode.HALF_UP), route, time, category, color, description);
	}

	private BigDecimal time(Instant foundAt, Instant from, Instant to, Duration window) {
		if (!foundAt.isBefore(from) && !foundAt.isAfter(to)) {
			return ONE;
		}
		Duration nearest = foundAt.isBefore(from) ? Duration.between(foundAt, from) : Duration.between(to, foundAt);
		return clamp(ONE.subtract(decimal(nearest).divide(decimal(window), DECIMAL64), DECIMAL64));
	}

	private BigDecimal decimal(Duration duration) {
		return BigDecimal.valueOf(duration.getSeconds())
				.add(BigDecimal.valueOf(duration.getNano(), 9), DECIMAL64);
	}

	private BigDecimal jaccard(String left, String right) {
		Set<String> leftTokens = tokens(left);
		Set<String> rightTokens = tokens(right);
		if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
			return ZERO;
		}
		Set<String> intersection = new HashSet<>(leftTokens);
		intersection.retainAll(rightTokens);
		Set<String> union = new HashSet<>(leftTokens);
		union.addAll(rightTokens);
		return BigDecimal.valueOf(intersection.size()).divide(BigDecimal.valueOf(union.size()), DECIMAL64);
	}

	private BigDecimal color(String reportDescription, String candidateColor) {
		if (candidateColor == null) {
			return ZERO;
		}
		String normalizedCandidate = Normalizer.normalize(candidateColor, Normalizer.Form.NFKC)
				.trim().toUpperCase(Locale.ROOT);
		if (!PALETTE.contains(normalizedCandidate)) {
			return ZERO;
		}
		Matcher matcher = TOKEN.matcher(Normalizer.normalize(reportDescription, Normalizer.Form.NFKC)
				.toUpperCase(Locale.ROOT));
		while (matcher.find()) {
			String reportColor = matcher.group();
			if (PALETTE.contains(reportColor)) {
				return normalizedCandidate.equals(reportColor) ? ONE : ZERO;
			}
		}
		return ZERO;
	}

	private Set<String> tokens(String value) {
		Matcher matcher = TOKEN.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC)
				.toLowerCase(Locale.ROOT));
		Set<String> tokens = new HashSet<>();
		while (matcher.find()) {
			tokens.add(matcher.group());
		}
		return tokens;
	}

	private BigDecimal clamp(BigDecimal value) {
		return value.max(ZERO).min(ONE);
	}

	public record MatchInputs(
			BigDecimal distanceMeters,
			int radiusMeters,
			Instant foundAt,
			Instant lostAtFrom,
			Instant lostAtTo,
			Duration timeWindow,
			String reportCategory,
			String itemCategory,
			String reportDescription,
			String color,
			String description
	) {
	}
}
