package kr.lostory.backend.lostreport.presentation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CreateLostReportRequest(
		@NotBlank @Size(max = 64) String category,
		@NotBlank @Size(max = 1000) String description,
		@NotNull Instant lostAtFrom,
		@NotNull Instant lostAtTo,
		@NotEmpty @Size(max = 10) List<@NotNull @Valid LostReportWaypointRequest> waypoints
) {
}
