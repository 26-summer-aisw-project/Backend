package kr.lostory.backend.lostreport.application;

import static kr.lostory.backend.lostcenter.application.LostCenterService.P0_NEARBY_RADIUS_METERS;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.lostory.backend.config.LostCenterProperties;
import kr.lostory.backend.config.LostReportProperties;
import kr.lostory.backend.config.MatchingProperties;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository.NearbyLostCenterProjection;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import kr.lostory.backend.lostreport.domain.ReportWaypoint;
import kr.lostory.backend.lostreport.domain.ReportWaypointRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class LostReportSnapshotService {

	private static final BigDecimal MINIMUM_LATITUDE = new BigDecimal("-90");
	private static final BigDecimal MAXIMUM_LATITUDE = new BigDecimal("90");
	private static final BigDecimal MINIMUM_LONGITUDE = new BigDecimal("-180");
	private static final BigDecimal MAXIMUM_LONGITUDE = new BigDecimal("180");
	private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

	private final LostReportRepository reportRepository;
	private final ReportWaypointRepository waypointRepository;
	private final LostCenterRepository centerRepository;
	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final LostReportProperties reportProperties;
	private final MatchingProperties matchingProperties;
	private final LostCenterProperties centerProperties;
	private final DynamicRadiusPolicy radiusPolicy;

	public LostReportSnapshotService(
			LostReportRepository reportRepository,
			ReportWaypointRepository waypointRepository,
			LostCenterRepository centerRepository,
			JdbcTemplate jdbc,
			ObjectMapper objectMapper,
			Clock clock,
			LostReportProperties reportProperties,
			MatchingProperties matchingProperties,
			LostCenterProperties centerProperties
	) {
		this.reportRepository = reportRepository;
		this.waypointRepository = waypointRepository;
		this.centerRepository = centerRepository;
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.reportProperties = reportProperties;
		this.matchingProperties = matchingProperties;
		this.centerProperties = centerProperties;
		this.radiusPolicy = new DynamicRadiusPolicy(matchingProperties);
	}

	@Transactional
	public LostReportSnapshot create(LostReportSnapshotCommand command) {
		return create(command, clock.instant());
	}

	LostReportSnapshot create(LostReportSnapshotCommand command, Instant now) {
		validateCommand(command);
		List<LostReportWaypointInput> waypoints = normalizeWaypoints(command.waypoints());
		int radius = radiusPolicy.calculate(adjacentDistances(waypoints));
		List<CenterGuidance> guidance = centerGuidance(waypoints);
		LostReport report = reportRepository.saveAndFlush(new LostReport(
				command.reporterId(), command.category(), command.lostAtFrom(), command.lostAtTo(),
				command.description(), radius, matchingProperties.radiusPolicyVersion(), json(guidance),
				now, reportProperties.ttl()
		));
		persistWaypoints(report.getId(), waypoints, now);
		return snapshot(report, guidance, waypoints);
	}

	@Transactional
	public LostReportSnapshot update(Long reportId, LostReportSnapshotCommand command) {
		validateCommand(command);
		LostReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow();
		List<LostReportWaypointInput> waypoints = normalizeWaypoints(command.waypoints());
		int radius = radiusPolicy.calculate(adjacentDistances(waypoints));
		List<CenterGuidance> guidance = centerGuidance(waypoints);
		Instant now = clock.instant();
		report.replaceSnapshotInputs(
				command.category(), command.lostAtFrom(), command.lostAtTo(), command.description(), radius,
				matchingProperties.radiusPolicyVersion(), json(guidance), now
		);
		waypointRepository.deleteAllByReportId(reportId);
		waypointRepository.flush();
		persistWaypoints(reportId, waypoints, now);
		return snapshot(report, guidance, waypoints);
	}

	@Transactional(readOnly = true)
	public LostReportSnapshot readSnapshot(Long reportId) {
		LostReport report = reportRepository.findById(reportId).orElseThrow();
		List<CenterGuidance> guidance = List.of(objectMapper.readValue(
				report.getCenterGuidance(), CenterGuidance[].class
		));
		List<LostReportWaypointInput> waypoints = waypointRepository.findAllByReportIdOrderByOrdinalAsc(reportId)
				.stream()
				.map(waypoint -> new LostReportWaypointInput(
						waypoint.getOrdinal(),
						BigDecimal.valueOf(waypoint.getLocation().getY()).setScale(7, RoundingMode.HALF_UP),
						BigDecimal.valueOf(waypoint.getLocation().getX()).setScale(7, RoundingMode.HALF_UP),
						waypoint.getPlaceName()
				))
				.toList();
		return snapshot(report, guidance, waypoints);
	}

	public List<LostReportWaypointInput> normalizeWaypoints(List<LostReportWaypointInput> inputs) {
		if (inputs == null || inputs.isEmpty()) {
			throw new IllegalArgumentException("waypoints must contain between 1 and 10 normalized coordinates");
		}
		Map<CoordinatePair, LostReportWaypointInput> unique = new LinkedHashMap<>();
		for (int index = 0; index < inputs.size(); index++) {
			LostReportWaypointInput input = Objects.requireNonNull(inputs.get(index), "waypoint must not be null");
			int expectedOrdinal = index + 1;
			if (input.ordinal() != expectedOrdinal) {
				throw new IllegalArgumentException("waypoint ordinal must match request order");
			}
			BigDecimal latitude = coordinate(input.latitude(), MINIMUM_LATITUDE, MAXIMUM_LATITUDE, "latitude");
			BigDecimal longitude = coordinate(input.longitude(), MINIMUM_LONGITUDE, MAXIMUM_LONGITUDE, "longitude");
			unique.putIfAbsent(
					new CoordinatePair(latitude, longitude),
					new LostReportWaypointInput(0, latitude, longitude, input.placeName())
			);
		}
		if (unique.isEmpty() || unique.size() > 10) {
			throw new IllegalArgumentException("waypoints must contain between 1 and 10 normalized coordinates");
		}
		List<LostReportWaypointInput> normalized = new ArrayList<>(unique.size());
		int ordinal = 1;
		for (LostReportWaypointInput input : unique.values()) {
			normalized.add(new LostReportWaypointInput(
					ordinal++, input.latitude(), input.longitude(), input.placeName()
			));
		}
		return List.copyOf(normalized);
	}

	private void validateCommand(LostReportSnapshotCommand command) {
		Objects.requireNonNull(command, "command must not be null");
		if (command.lostAtFrom() == null || command.lostAtTo() == null
				|| command.lostAtFrom().isAfter(command.lostAtTo())) {
			throw new IllegalArgumentException("lostAtFrom must not be after lostAtTo");
		}
	}

	private BigDecimal coordinate(BigDecimal value, BigDecimal minimum, BigDecimal maximum, String name) {
		if (value == null || value.compareTo(minimum) < 0 || value.compareTo(maximum) > 0) {
			throw new IllegalArgumentException(name + " is outside its valid range");
		}
		return value.setScale(7, RoundingMode.HALF_UP);
	}

	private List<BigDecimal> adjacentDistances(List<LostReportWaypointInput> waypoints) {
		List<BigDecimal> distances = new ArrayList<>();
		for (int index = 1; index < waypoints.size(); index++) {
			LostReportWaypointInput previous = waypoints.get(index - 1);
			LostReportWaypointInput current = waypoints.get(index);
			Double rawDistance = jdbc.queryForObject("""
					SELECT ST_Distance(
						ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
						ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
					)
					""", Double.class,
					previous.longitude(), previous.latitude(), current.longitude(), current.latitude());
			distances.add(BigDecimal.valueOf(Objects.requireNonNull(rawDistance)));
		}
		return distances;
	}

	private List<CenterGuidance> centerGuidance(List<LostReportWaypointInput> waypoints) {
		int guidanceLimit = Math.min(10, centerProperties.nearbyLimit());
		Map<Long, GuidanceCandidate> candidates = new LinkedHashMap<>();
		for (LostReportWaypointInput waypoint : waypoints) {
			for (NearbyLostCenterProjection center : centerRepository.findNearby(
					waypoint.latitude(), waypoint.longitude(), P0_NEARBY_RADIUS_METERS, guidanceLimit
			)) {
				BigDecimal rawDistance = BigDecimal.valueOf(center.getDistanceMeters());
				GuidanceCandidate candidate = new GuidanceCandidate(center, rawDistance);
				candidates.merge(center.getId(), candidate,
						(previous, replacement) -> previous.rawDistance().compareTo(replacement.rawDistance()) <= 0
								? previous : replacement);
			}
		}
		return candidates.values().stream()
				.sorted(Comparator.comparing(GuidanceCandidate::rawDistance)
						.thenComparing(candidate -> candidate.center().getId()))
				.limit(guidanceLimit)
				.map(candidate -> new CenterGuidance(
						candidate.center().getId().toString(),
						candidate.center().getName(),
						candidate.center().getPhoneNumber(),
						candidate.rawDistance().setScale(0, RoundingMode.HALF_UP).intValueExact()
				))
				.toList();
	}

	private void persistWaypoints(Long reportId, List<LostReportWaypointInput> waypoints, Instant now) {
		List<ReportWaypoint> entities = waypoints.stream()
				.map(waypoint -> new ReportWaypoint(
						reportId,
						(short) waypoint.ordinal(),
						waypoint.placeName(),
						GEOMETRY_FACTORY.createPoint(new Coordinate(
								waypoint.longitude().doubleValue(), waypoint.latitude().doubleValue()
						)),
						now
				))
				.toList();
		waypointRepository.saveAllAndFlush(entities);
	}

	private String json(List<CenterGuidance> guidance) {
		return objectMapper.writeValueAsString(guidance);
	}

	private LostReportSnapshot snapshot(
			LostReport report,
			List<CenterGuidance> guidance,
			List<LostReportWaypointInput> waypoints
	) {
		return new LostReportSnapshot(
				report.getId(), report.getEffectiveSearchRadiusMeters(), report.getRadiusPolicyVersion(),
				guidance, waypoints, report.getExpiredAt()
		);
	}

	private record CoordinatePair(BigDecimal latitude, BigDecimal longitude) {
	}

	private record GuidanceCandidate(NearbyLostCenterProjection center, BigDecimal rawDistance) {
	}
}
