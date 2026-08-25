package kr.lostory.backend.lostreport.application;

import java.time.Instant;
import java.util.List;

public record LostReportSnapshot(
		Long id,
		int effectiveSearchRadiusMeters,
		String radiusPolicyVersion,
		List<CenterGuidance> centerGuidance,
		List<LostReportWaypointInput> waypoints,
		Instant expiredAt
) {
	public LostReportSnapshot {
		centerGuidance = List.copyOf(centerGuidance);
		waypoints = List.copyOf(waypoints);
	}
}
