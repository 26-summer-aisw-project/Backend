package kr.lostory.backend;

import static java.math.MathContext.DECIMAL64;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.lostory.backend.lostreport.application.LostReportMatchingService;
import kr.lostory.backend.lostreport.application.MatchCandidateResult;
import kr.lostory.backend.lostreport.application.MatchScore;
import kr.lostory.backend.lostreport.application.MatchScoreCalculator;
import kr.lostory.backend.lostreport.application.MatchScoreCalculator.MatchInputs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "matching.time-window=PT2H")
@Import(PostgresTestContainerConfig.class)
@ActiveProfiles("test")
class MatchScoringIntegrationTest {

	@Autowired LostReportMatchingService matchingService;
	@Autowired MatchScoreCalculator calculator;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper objectMapper;

	MatchScoringDatabaseFixture fixture;

	@BeforeEach
	void setUp() {
		fixture = new MatchScoringDatabaseFixture(jdbc);
		fixture.clean();
	}

	@Test
	void allComponentsUseRawPostgisDistanceConfiguredTimeAndFinderPrecedence() throws Exception {
		Long userId = fixture.user();
		Long reportId = fixture.report(userId, "WALLET", "ＢＬＡＣＫ black wallet wallet", 1000);
		Long itemId = fixture.activeItem(userId, "WALLET",
				MatchScoringDatabaseFixture.LOST_TO.plus(Duration.ofHours(1)), 250.0);
		fixture.feature(itemId, "COLOR", "BLACK", 1, "AI", "MATCH_ONLY");
		fixture.feature(itemId, "COLOR", "WHITE", 2, "FINDER", "CANDIDATE_VIEW");
		fixture.feature(itemId, "COLOR", "BLACK", 1, "FINDER", "CANDIDATE_VIEW");
		fixture.feature(itemId, "COLOR", "BLUE", 1, "FINDER", "CANDIDATE_VIEW");
		fixture.feature(itemId, "LABEL", "ignored ai label", 1, "AI", "MATCH_ONLY");
		fixture.feature(itemId, "PUBLIC_DESCRIPTION", "wrong later", 2, "FINDER", "CANDIDATE_VIEW");
		fixture.feature(itemId, "PUBLIC_DESCRIPTION", "black wallet", 1, "FINDER", "CANDIDATE_VIEW");
		fixture.feature(itemId, "PUBLIC_DESCRIPTION", "black", 1, "FINDER", "CANDIDATE_VIEW");

		List<MatchCandidateResult> result = matchingService.recompute(reportId);

		assertThat(result).containsExactly(new MatchCandidateResult(itemId.toString(), (short) 1,
				new BigDecimal("81.25")));
		JsonNode breakdown = persistedBreakdown(reportId);
		assertThat(breakdown.get("route").decimalValue()).isEqualByComparingTo(new BigDecimal("0.75"));
		assertThat(breakdown.get("time").decimalValue()).isEqualByComparingTo(new BigDecimal("0.5"));
		assertThat(breakdown.get("category").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(breakdown.get("color").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(breakdown.get("description").decimalValue()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(breakdown.has("resolvedColor")).isFalse();
		assertThat(breakdown.has("resolvedDescription")).isFalse();
		assertReportMatched(reportId);
	}

	@Test
	void aiFallbackAndFinderOverrideProduceDifferentProductionScores() throws Exception {
		Long userId = fixture.user();
		Long reportId = fixture.report(userId, "WALLET", "black", 1000);
		Long finderItem = fixture.activeItem(userId, "WALLET", MatchScoringDatabaseFixture.LOST_FROM, 0);
		Long aiItem = fixture.activeItem(userId, "WALLET", MatchScoringDatabaseFixture.LOST_FROM, 0);
		fixture.feature(finderItem, "COLOR", "BLACK", 1, "AI", "MATCH_ONLY");
		fixture.feature(finderItem, "COLOR", "WHITE", 1, "FINDER", "CANDIDATE_VIEW");
		fixture.feature(aiItem, "COLOR", "BLACK", 1, "AI", "MATCH_ONLY");

		List<MatchCandidateResult> result = matchingService.recompute(reportId);

		assertThat(result).extracting(MatchCandidateResult::candidateId)
				.containsExactly(aiItem.toString(), finderItem.toString());
		assertThat(result).extracting(MatchCandidateResult::score)
				.containsExactly(new BigDecimal("90.00"), new BigDecimal("75.00"));
	}

	@Test
	void configuredTimeWindowReachesZeroAtExactBoundary() throws Exception {
		Long userId = fixture.user();
		Long reportId = fixture.report(userId, "WALLET", "unknown", 1000);
		fixture.activeItem(userId, "OTHER", MatchScoringDatabaseFixture.LOST_TO.plus(Duration.ofHours(2)), 0);

		matchingService.recompute(reportId);

		assertThat(persistedBreakdown(reportId).get("time").decimalValue()).isZero();
	}

	@Test
	void tokenizationMissingComponentsAndFinalOnlyRoundingFollowDecimal64() {
		MatchInputs input = new MatchInputs(new BigDecimal("333.3333333333333"), 1000,
				MatchScoringDatabaseFixture.LOST_FROM.minus(Duration.ofHours(1)),
				MatchScoringDatabaseFixture.LOST_FROM, MatchScoringDatabaseFixture.LOST_TO,
				Duration.ofHours(2), "WALLET", "OTHER", "ＡＢＣ abc 한글-１２３", null, "abc 한글 123");

		MatchScore score = calculator.calculate(input);

		BigDecimal rawRoute = BigDecimal.ONE.subtract(input.distanceMeters()
				.divide(BigDecimal.valueOf(1000), DECIMAL64), DECIMAL64);
		BigDecimal rawDescription = new BigDecimal("3").divide(new BigDecimal("3"), DECIMAL64);
		BigDecimal expected = rawRoute.multiply(new BigDecimal(".35"), DECIMAL64)
				.add(new BigDecimal(".5").multiply(new BigDecimal(".20"), DECIMAL64), DECIMAL64)
				.add(rawDescription.multiply(new BigDecimal(".10"), DECIMAL64), DECIMAL64)
				.multiply(BigDecimal.valueOf(100), DECIMAL64).setScale(2, RoundingMode.HALF_UP);
		assertThat(score.route()).isEqualByComparingTo(rawRoute);
		assertThat(score.time()).isEqualByComparingTo(new BigDecimal(".5"));
		assertThat(score.category()).isZero();
		assertThat(score.color()).isZero();
		assertThat(score.description()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(score.score()).isEqualByComparingTo(expected);
	}

	@Test
	void colorUsesBinaryNormalizedPaletteEqualityNotTokenJaccard() {
		MatchInputs equal = new MatchInputs(BigDecimal.ZERO, 1000,
				MatchScoringDatabaseFixture.LOST_FROM, MatchScoringDatabaseFixture.LOST_FROM,
				MatchScoringDatabaseFixture.LOST_TO, Duration.ofHours(2), "A", "B",
				"ＢＬＡＣＫ wallet", "black", null);
		MatchInputs partialToken = new MatchInputs(BigDecimal.ZERO, 1000,
				MatchScoringDatabaseFixture.LOST_FROM, MatchScoringDatabaseFixture.LOST_FROM,
				MatchScoringDatabaseFixture.LOST_TO, Duration.ofHours(2), "A", "B",
				"black wallet", "black wallet", null);

		assertThat(calculator.calculate(equal).color()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(calculator.calculate(partialToken).color()).isZero();
	}

	@Test
	void activeUnexpiredPoolUsesExactRadiusAndExcludesEveryOtherLifecycleState() {
		Long userId = fixture.user();
		Long reportId = fixture.report(userId, "BAG", "bag", 1000);
		Long exactRadius = fixture.activeItem(userId, "BAG", MatchScoringDatabaseFixture.LOST_FROM, 1000.0);
		fixture.activeItem(userId, "BAG", MatchScoringDatabaseFixture.LOST_FROM, 1000.01);
		fixture.expiredActiveItem(userId, "BAG", 0);
		fixture.draftItem(userId);
		fixture.pendingHandoverItem(userId, "BAG", 0);
		fixture.terminalItem(userId, "BAG", "EXPIRED", 0);
		fixture.terminalItem(userId, "BAG", "RETURNED", 0);

		List<MatchCandidateResult> result = matchingService.recompute(reportId);

		assertThat(result).extracting(MatchCandidateResult::candidateId).containsExactly(exactRadius.toString());
		assertThat(result.getFirst().score()).isEqualByComparingTo(new BigDecimal("40.00"));
	}

	@Test
	void topFiveSortsEqualFinalScoresByImmutableFoundItemId() {
		Long userId = fixture.user();
		Long reportId = fixture.report(userId, "BAG", "unknown", 1000);
		List<Long> ids = java.util.stream.IntStream.range(0, 7)
				.mapToObj(index -> fixture.activeItem(userId, "BAG",
						MatchScoringDatabaseFixture.LOST_FROM, 0)).toList();

		List<MatchCandidateResult> result = matchingService.recompute(reportId);

		assertThat(result).hasSize(5);
		assertThat(result).extracting(MatchCandidateResult::candidateId)
				.containsExactlyElementsOf(ids.subList(0, 5).stream().map(Object::toString).toList());
		assertThat(result).extracting(MatchCandidateResult::rank).containsExactly((short) 1, (short) 2,
				(short) 3, (short) 4, (short) 5);
		assertThat(jdbc.queryForList("SELECT item_id FROM match_candidates WHERE report_id = ? ORDER BY rank",
				Long.class, reportId)).containsExactlyElementsOf(ids.subList(0, 5));
	}

	@Test
	void zeroEligibleCandidatesAtomicallyClearsPreviousSetAndMarksFresh() {
		Long userId = fixture.user();
		Long reportId = fixture.report(userId, "BAG", "unknown", 1000);
		Long farItem = fixture.activeItem(userId, "BAG", MatchScoringDatabaseFixture.LOST_FROM, 1000.01);
		jdbc.update("""
				INSERT INTO match_candidates (report_id, item_id, rank, score, score_breakdown, created_at)
				VALUES (?, ?, 1, 99.00, '{}', clock_timestamp())
				""", reportId, farItem);

		List<MatchCandidateResult> result = matchingService.recompute(reportId);

		assertThat(result).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM match_candidates WHERE report_id = ?",
				Integer.class, reportId)).isZero();
		assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
				Boolean.class, reportId)).isFalse();
	}

	@Test
	void closedOrExpiredReportCannotReplaceCandidates() {
		Long userId = fixture.user();
		Long reportId = fixture.report(userId, "BAG", "bag", 1000);
		jdbc.update("UPDATE lost_reports SET status = 'CLOSED' WHERE id = ?", reportId);

		assertThatThrownBy(() -> matchingService.recompute(reportId))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("only unexpired OPEN reports can be matched");
	}

	@Test
	void concurrentRecomputeLeavesOneDeterministicAtomicSet() throws Exception {
		Long userId = fixture.user();
		Long reportId = fixture.report(userId, "BAG", "bag", 1000);
		List<Long> ids = java.util.stream.IntStream.range(0, 6)
				.mapToObj(index -> fixture.activeItem(userId, "BAG",
						MatchScoringDatabaseFixture.LOST_FROM, 0)).toList();
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<List<MatchCandidateResult>> first = executor.submit(() -> {
				start.await();
				return matchingService.recompute(reportId);
			});
			Future<List<MatchCandidateResult>> second = executor.submit(() -> {
				start.await();
				return matchingService.recompute(reportId);
			});
			start.countDown();

			assertThat(first.get()).isEqualTo(second.get());
		} finally {
			executor.shutdownNow();
		}
		assertThat(jdbc.queryForList("SELECT item_id FROM match_candidates WHERE report_id = ? ORDER BY rank",
				Long.class, reportId)).containsExactlyElementsOf(ids.subList(0, 5));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM match_candidates WHERE report_id = ?",
				Integer.class, reportId)).isEqualTo(5);
	}

	private JsonNode persistedBreakdown(Long reportId) throws Exception {
		return objectMapper.readTree(jdbc.queryForObject(
				"SELECT score_breakdown::text FROM match_candidates WHERE report_id = ? AND rank = 1",
				String.class, reportId));
	}

	private void assertReportMatched(Long reportId) {
		assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
				Boolean.class, reportId)).isFalse();
		assertThat(jdbc.queryForObject("SELECT matching_policy_version FROM lost_reports WHERE id = ?",
				String.class, reportId)).isEqualTo("matching-v1");
		assertThat(jdbc.queryForObject("SELECT last_matched_at FROM lost_reports WHERE id = ?",
				Timestamp.class, reportId)).isNotNull();
		assertThat(persistedBreakdownPolicy(reportId)).isEqualTo("matching-v1");
	}

	private String persistedBreakdownPolicy(Long reportId) {
		return jdbc.queryForObject("""
				SELECT score_breakdown ->> 'policyVersion'
				FROM match_candidates WHERE report_id = ? AND rank = 1
				""", String.class, reportId);
	}
}
