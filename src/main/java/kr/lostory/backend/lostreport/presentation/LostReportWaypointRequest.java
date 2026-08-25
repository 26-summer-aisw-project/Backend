package kr.lostory.backend.lostreport.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record LostReportWaypointRequest(
		@Min(1) @Max(10) int ordinal,
		@NotNull @Valid LostReportPointRequest point
) {
}
