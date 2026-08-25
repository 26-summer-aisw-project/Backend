package kr.lostory.backend.lostreport.application;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import kr.lostory.backend.config.MatchingProperties;
import kr.lostory.backend.founditem.application.MatchingFeatureResolver;
import kr.lostory.backend.founditem.application.MatchingFeatureResolver.MatchingFeatures;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import kr.lostory.backend.lostreport.domain.LostReportStatus;
import kr.lostory.backend.lostreport.domain.MatchCandidate;
import kr.lostory.backend.lostreport.domain.MatchCandidateRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class LostReportMatchingService {

	static final String POLICY_VERSION = "matching-v1";

	private final LostReportRepository reportRepository;
	private final MatchCandidateRepository candidateRepository;
	private final JdbcTemplate jdbc;
	private final MatchingFeatureResolver featureResolver;
	private final MatchingProperties properties;
	private final MatchScoreCalculator calculator;
	private final ObjectMapper objectMapper;

	public LostReportMatchingService(
			LostReportRepository reportRepository,
			MatchCandidateRepository candidateRepository,
			JdbcTemplate jdbc,
			MatchingFeatureResolver featureResolver,
			MatchingProperties properties,
			MatchScoreCalculator calculator,
			ObjectMapper objectMapper
	) {
		this.reportRepository = reportRepository;
		this.candidateRepository = candidateRepository;
		this.jdbc = jdbc;
		this.featureResolver = featureResolver;
		this.properties = properties;
		this.calculator = calculator;
		this.objectMapper = objectMapper;
	}

	@Transactional
	public List<MatchCandidateResult> recompute(Long reportId) {
		LostReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow();
		Instant databaseNow = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
		return recompute(report, databaseNow, databaseNow);
	}

	@Transactional
	public List<MatchCandidateResult> recomputeForOpenReport(Long reportId, Instant reportNow) {
		LostReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow();
		Instant databaseNow = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
		return recompute(report, reportNow, databaseNow);
	}

	private List<MatchCandidateResult> recompute(
			LostReport report,
			Instant reportNow,
			Instant matchedAt
	) {
		if (report.getStatus() != LostReportStatus.OPEN || !report.getExpiredAt().isAfter(reportNow)) {
			throw new IllegalStateException("only unexpired OPEN reports can be matched");
		}

		List<ScoredCandidate> scored = candidates(report).stream()
				.map(candidate -> score(report, candidate))
				.sorted(Comparator.comparing(ScoredCandidate::scoreValue).reversed()
						.thenComparing(ScoredCandidate::itemId))
				.limit(5)
				.toList();
		candidateRepository.deleteAllByReportId(report.getId());
		List<MatchCandidate> entities = new ArrayList<>(scored.size());
		List<MatchCandidateResult> result = new ArrayList<>(scored.size());
		for (int index = 0; index < scored.size(); index++) {
			ScoredCandidate candidate = scored.get(index);
			short rank = (short) (index + 1);
			entities.add(new MatchCandidate(report.getId(), candidate.itemId(), rank,
					candidate.score().score(), breakdown(candidate)));
			result.add(new MatchCandidateResult(candidate.itemId().toString(), rank, candidate.score().score()));
		}
		candidateRepository.saveAllAndFlush(entities);
		report.recordMatch(matchedAt, POLICY_VERSION);
		return List.copyOf(result);
	}

	private List<CandidateRow> candidates(LostReport report) {
		return jdbc.query("""
				SELECT item.id, item.category, item.found_at,
				       MIN(ST_Distance(item.found_location, waypoint.location)) AS route_distance
				FROM found_items item
				JOIN report_waypoints waypoint ON waypoint.report_id = ?
				WHERE item.status = 'ACTIVE'
				  AND item.expired_at > CURRENT_TIMESTAMP
				  AND item.found_location IS NOT NULL
				GROUP BY item.id, item.category, item.found_at
				HAVING MIN(ST_Distance(item.found_location, waypoint.location)) <= ?
				ORDER BY item.id
				""", (rs, rowNum) -> new CandidateRow(
					rs.getLong("id"), rs.getString("category"), rs.getTimestamp("found_at").toInstant(),
					BigDecimal.valueOf(rs.getDouble("route_distance"))),
				report.getId(), report.getEffectiveSearchRadiusMeters());
	}

	private ScoredCandidate score(LostReport report, CandidateRow candidate) {
		MatchingFeatures features = featureResolver.resolveForMatching(candidate.itemId());
		MatchScore score = calculator.calculate(new MatchScoreCalculator.MatchInputs(
				candidate.distanceMeters(), report.getEffectiveSearchRadiusMeters(), candidate.foundAt(),
				report.getLostAtFrom(), report.getLostAtTo(), properties.timeWindow(),
				report.getCategory(), candidate.category(), report.getDescription(),
				features.color().orElse(null), features.description().orElse(null)));
		return new ScoredCandidate(candidate.itemId(), score);
	}

	private String breakdown(ScoredCandidate candidate) {
		return objectMapper.writeValueAsString(new ScoreBreakdown(
				POLICY_VERSION, candidate.score().route(), candidate.score().time(),
				candidate.score().category(), candidate.score().color(), candidate.score().description()));
	}

	private record CandidateRow(Long itemId, String category, Instant foundAt, BigDecimal distanceMeters) {
	}

	private record ScoredCandidate(Long itemId, MatchScore score) {
		BigDecimal scoreValue() {
			return score.score();
		}
	}

	private record ScoreBreakdown(
			String policyVersion,
			BigDecimal route,
			BigDecimal time,
			BigDecimal category,
			BigDecimal color,
			BigDecimal description
	) {
	}
}
