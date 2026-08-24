package kr.lostory.backend.lostreport.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record UpdateLostReportRequest(
		@Size(min = 1, max = 64) String category,
		@Size(min = 1, max = 1000) String description,
		Instant lostAtFrom,
		Instant lostAtTo,
		@Size(min = 1, max = 10) List<@NotNull @Valid LostReportWaypointRequest> waypoints
) {
	public boolean hasChanges() {
		return category != null || description != null || lostAtFrom != null || lostAtTo != null || waypoints != null;
	}
}
