package kr.lostory.backend.lostreport.application;

import java.time.Instant;
import java.util.List;

public record LostReportSnapshotCommand(
		Long reporterId,
		String category,
		Instant lostAtFrom,
		Instant lostAtTo,
		String description,
		List<LostReportWaypointInput> waypoints
) {
}
