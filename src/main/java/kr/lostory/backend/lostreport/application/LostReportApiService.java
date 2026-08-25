package kr.lostory.backend.lostreport.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import kr.lostory.backend.lostreport.domain.LostReportStatus;
import kr.lostory.backend.lostreport.presentation.CreateLostReportRequest;
import kr.lostory.backend.lostreport.presentation.LostReportResponses;
import kr.lostory.backend.lostreport.presentation.LostReportWaypointRequest;
import kr.lostory.backend.lostreport.presentation.UpdateLostReportRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LostReportApiService {

	private final LostReportRepository reportRepository;
	private final LostReportSnapshotService snapshotService;
	private final LostReportMatchingService matchingService;
	private final LostReportLifecycleCleanupService lifecycle;
	private final Clock clock;

	public LostReportApiService(
			LostReportRepository reportRepository,
			LostReportSnapshotService snapshotService,
			LostReportMatchingService matchingService,
			LostReportLifecycleCleanupService lifecycle,
			Clock clock
	) {
		this.reportRepository = reportRepository;
		this.snapshotService = snapshotService;
		this.matchingService = matchingService;
		this.lifecycle = lifecycle;
		this.clock = clock;
	}

	@Transactional
	public LostReportResponses.Create create(Long reporterId, CreateLostReportRequest request) {
		validateTime(request.lostAtFrom(), request.lostAtTo());
		Instant reportNow = clock.instant();
		LostReportSnapshot snapshot = snapshotService.create(new LostReportSnapshotCommand(
				reporterId, text(request.category()), request.lostAtFrom(), request.lostAtTo(),
				text(request.description()), waypoints(request.waypoints())
		), reportNow);
		matchingService.recomputeForOpenReport(snapshot.id(), reportNow);
		LostReport report = reportRepository.findById(snapshot.id()).orElseThrow();
		return new LostReportResponses.Create(
				report.getId().toString(), report.getStatus(), snapshot.effectiveSearchRadiusMeters(),
				snapshot.radiusPolicyVersion(), snapshot.centerGuidance(), report.isCandidatesStale()
		);
	}

	@Transactional
	public LostReportResponses.Update update(Long reportId, Long requesterId, UpdateLostReportRequest request) {
		if (!request.hasChanges()) {
			throw new LostoryException(ErrorCode.INVALID_REQUEST);
		}
		Instant reportNow = lifecycle.requireOpen(reportId, requesterId);
		LostReport current = reportRepository.findByIdForUpdate(reportId).orElseThrow();
		LostReportSnapshot currentSnapshot = snapshotService.readSnapshot(reportId);
		String category = request.category() == null ? current.getCategory() : text(request.category());
		String description = request.description() == null ? current.getDescription() : text(request.description());
		Instant from = request.lostAtFrom() == null ? current.getLostAtFrom() : request.lostAtFrom();
		Instant to = request.lostAtTo() == null ? current.getLostAtTo() : request.lostAtTo();
		validateTime(from, to);
		List<LostReportWaypointInput> waypoints = request.waypoints() == null
				? currentSnapshot.waypoints() : waypoints(request.waypoints());
		LostReportSnapshot updated = snapshotService.update(reportId, new LostReportSnapshotCommand(
				requesterId, category, from, to, description, waypoints
		));
		matchingService.recomputeForOpenReport(reportId, reportNow);
		return new LostReportResponses.Update(
				reportId.toString(), updated.effectiveSearchRadiusMeters(), updated.centerGuidance(),
				current.isCandidatesStale()
		);
	}

	@Transactional
	public LostReportResponses.Detail detail(Long reportId, Long requesterId) {
		lifecycle.applyExpiry(reportId);
		LostReport report = owned(reportId, requesterId);
		LostReportSnapshot snapshot = snapshotService.readSnapshot(reportId);
		return new LostReportResponses.Detail(
				report.getId().toString(), report.getStatus(), report.getCategory(), report.getDescription(),
				report.getLostAtFrom(), report.getLostAtTo(), snapshot.waypoints().stream()
						.map(waypoint -> new LostReportResponses.Waypoint(
								waypoint.ordinal(), new LostReportResponses.Point(
										waypoint.latitude(), waypoint.longitude())))
						.toList(),
				report.getEffectiveSearchRadiusMeters(), report.getRadiusPolicyVersion(), snapshot.centerGuidance(),
				report.isCandidatesStale(), report.getExpiredAt(), report.getCreatedAt(), report.getUpdatedAt()
		);
	}

	@Transactional
	public LostReportResponses.ListResult list(
			Long requesterId, LostReportStatus status, int page, int pageSize
	) {
		lifecycle.runCleanup();
		PageRequest pageable = PageRequest.of(page - 1, pageSize,
				Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
		Page<LostReport> reports = status == null
				? reportRepository.findByReporterId(requesterId, pageable)
				: reportRepository.findByReporterIdAndStatus(requesterId, status, pageable);
		return new LostReportResponses.ListResult(
				reports.stream().map(report -> new LostReportResponses.Item(
						report.getId().toString(), report.getCategory(), report.getStatus(),
						report.getEffectiveSearchRadiusMeters(), report.isCandidatesStale())).toList(),
				new LostReportResponses.Meta(page, pageSize, reports.getTotalElements())
		);
	}

	@Transactional
	public LostReportResponses.Close close(Long reportId, Long requesterId) {
		lifecycle.requireOpen(reportId, requesterId);
		LostReport report = reportRepository.findByIdForUpdate(reportId).orElseThrow();
		report.close(clock.instant().truncatedTo(ChronoUnit.MICROS));
		return new LostReportResponses.Close(report.getId().toString(), report.getStatus());
	}

	private LostReport owned(Long reportId, Long requesterId) {
		LostReport report = reportRepository.findById(reportId)
				.orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
		if (!report.getReporterId().equals(requesterId)) {
			throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
		}
		return report;
	}

	private List<LostReportWaypointInput> waypoints(List<LostReportWaypointRequest> requests) {
		for (int index = 0; index < requests.size(); index++) {
			if (requests.get(index).ordinal() != index + 1) {
				throw new LostoryException(ErrorCode.INVALID_REQUEST);
			}
		}
		return requests.stream().map(waypoint -> new LostReportWaypointInput(
				waypoint.ordinal(), waypoint.point().latitude(), waypoint.point().longitude(), null)).toList();
	}

	private String text(String value) {
		if (!StringUtils.hasText(value)) {
			throw new LostoryException(ErrorCode.INVALID_REQUEST);
		}
		return value.trim();
	}

	private void validateTime(Instant from, Instant to) {
		if (from.isAfter(to)) {
			throw new LostoryException(ErrorCode.INVALID_REQUEST);
		}
	}
}
