package kr.lostory.backend.lostreport.presentation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import kr.lostory.backend.lostreport.application.CenterGuidance;
import kr.lostory.backend.lostreport.domain.LostReportStatus;

public final class LostReportResponses {
	private LostReportResponses() {
	}

	public record Create(
			String id,
			LostReportStatus status,
			int effectiveSearchRadiusMeters,
			String radiusPolicyVersion,
			List<CenterGuidance> centerGuidance,
			boolean candidatesStale
	) {
	}

	public record Update(
			String id,
			int effectiveSearchRadiusMeters,
			List<CenterGuidance> centerGuidance,
			boolean candidatesStale
	) {
	}

	public record Detail(
			String id,
			LostReportStatus status,
			String category,
			String description,
			Instant lostAtFrom,
			Instant lostAtTo,
			List<Waypoint> waypoints,
			int effectiveSearchRadiusMeters,
			String radiusPolicyVersion,
			List<CenterGuidance> centerGuidance,
			boolean candidatesStale,
			Instant expiredAt,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record Waypoint(int ordinal, Point point) {
	}

	public record Point(BigDecimal latitude, BigDecimal longitude) {
	}

	public record ListResult(List<Item> data, Meta meta) {
	}

	public record Item(
			String id,
			String category,
			LostReportStatus status,
			int effectiveSearchRadiusMeters,
			boolean candidatesStale
	) {
	}

	public record Meta(int page, int pageSize, long totalItems) {
	}

	public record Close(String id, LostReportStatus status) {
	}
}
