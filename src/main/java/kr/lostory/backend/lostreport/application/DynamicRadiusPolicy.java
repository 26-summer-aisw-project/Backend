package kr.lostory.backend.lostreport.application;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kr.lostory.backend.config.MatchingProperties;

public final class DynamicRadiusPolicy {

	private static final MathContext CALCULATION_CONTEXT = MathContext.DECIMAL64;

	private final BigDecimal minimum;
	private final BigDecimal base;
	private final BigDecimal maximum;
	private final BigDecimal coefficient;

	public DynamicRadiusPolicy(MatchingProperties properties) {
		this(
				new BigDecimal(Integer.toString(properties.radiusMin()), CALCULATION_CONTEXT),
				new BigDecimal(Integer.toString(properties.radiusBase()), CALCULATION_CONTEXT),
				new BigDecimal(Integer.toString(properties.radiusMax()), CALCULATION_CONTEXT),
				properties.radiusCoefficient()
		);
	}

	public DynamicRadiusPolicy(BigDecimal minimum, BigDecimal base, BigDecimal maximum, BigDecimal coefficient) {
		this.minimum = decimalSnapshot(minimum);
		this.base = decimalSnapshot(base);
		this.maximum = decimalSnapshot(maximum);
		this.coefficient = decimalSnapshot(coefficient);
	}

	public int calculate(List<BigDecimal> adjacentDistances) {
		List<BigDecimal> sorted = new ArrayList<>(adjacentDistances);
		sorted.sort(Comparator.naturalOrder());
		BigDecimal median = median(sorted);
		BigDecimal calculated = base.add(coefficient.multiply(median, CALCULATION_CONTEXT), CALCULATION_CONTEXT);
		BigDecimal clamped = calculated.max(minimum).min(maximum);
		return clamped.setScale(0, RoundingMode.HALF_UP).intValueExact();
	}

	private BigDecimal median(List<BigDecimal> sorted) {
		if (sorted.isEmpty()) {
			return BigDecimal.ZERO;
		}
		int middle = sorted.size() / 2;
		if (sorted.size() % 2 == 1) {
			return sorted.get(middle);
		}
		return sorted.get(middle - 1).add(sorted.get(middle), CALCULATION_CONTEXT)
				.divide(BigDecimal.valueOf(2), CALCULATION_CONTEXT);
	}

	private BigDecimal decimalSnapshot(BigDecimal value) {
		return new BigDecimal(value.toPlainString(), CALCULATION_CONTEXT);
	}
}
