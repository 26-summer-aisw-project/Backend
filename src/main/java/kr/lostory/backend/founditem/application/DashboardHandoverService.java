package kr.lostory.backend.founditem.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.lostory.backend.audit.application.P0AuditService;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.CenterHandover;
import kr.lostory.backend.founditem.domain.CenterHandoverRepository;
import kr.lostory.backend.founditem.domain.CenterHandoverStatus;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.presentation.DashboardHandoverResponses;
import kr.lostory.backend.partner.domain.CenterPartnership;
import kr.lostory.backend.partner.domain.CenterPartnershipRepository;
import kr.lostory.backend.partner.domain.PartnershipStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardHandoverService {

    private final CenterHandoverRepository handovers;
    private final FoundItemRepository items;
    private final CenterPartnershipRepository partnerships;
    private final P0AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DashboardHandoverService(
            CenterHandoverRepository handovers,
            FoundItemRepository items,
            CenterPartnershipRepository partnerships,
            P0AuditService audit,
            JdbcTemplate jdbc,
            Clock clock
    ) {
        this.handovers = handovers;
        this.items = items;
        this.partnerships = partnerships;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardHandoverResponses.ListResponse list(Long managerId, CenterHandoverStatus status) {
        Long centerId = managerPartnership(managerId).getCenterId();
        List<DashboardHandoverResponses.Entry> data = handovers.findCurrentByCenterAndStatus(centerId, status).stream()
                .map(handover -> DashboardHandoverResponses.Entry.from(handover,
                        items.findById(handover.getFoundItemId())
                                .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE))))
                .toList();
        return new DashboardHandoverResponses.ListResponse(data);
    }

    @Transactional
    public DashboardHandoverResponses.AcceptResponse accept(
            Long handoverId,
            Long managerId,
            List<String> observedFeatures
    ) {
        if (observedFeatures.isEmpty()) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        CenterHandover requested = resolveRequested(handoverId, managerId);
        FoundItem item = items.findByIdForUpdate(requested.getFoundItemId())
                .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE));
        verifyCurrent(requested);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        item.acceptHandover();
        requested.accept(managerId, now);
        audit.handoverCenterAccepted(managerId, requested.getId());
        return DashboardHandoverResponses.AcceptResponse.from(requested);
    }

    @Transactional
    public DashboardHandoverResponses.RejectResponse reject(Long handoverId, Long managerId, String reason) {
        CenterHandover requested = resolveRequested(handoverId, managerId);
        items.findByIdForUpdate(requested.getFoundItemId())
                .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE));
        verifyCurrent(requested);
        requested.reject(managerId, reason.trim(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        audit.handoverCenterRejected(managerId, requested.getId());
        return DashboardHandoverResponses.RejectResponse.from(requested);
    }

    private CenterHandover resolveRequested(Long handoverId, Long managerId) {
        CenterPartnership partnership = managerPartnership(managerId);
        CenterHandover handover = handovers.findById(handoverId)
                .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE));
        if (!handover.getCenterId().equals(partnership.getCenterId())) {
            throw new LostoryException(ErrorCode.FORBIDDEN);
        }
        return handover;
    }

    private CenterPartnership managerPartnership(Long managerId) {
        return partnerships.findByManagerUserIdAndStatus(managerId, PartnershipStatus.ACTIVE)
                .orElseThrow(() -> new LostoryException(ErrorCode.FORBIDDEN));
    }

    private void verifyCurrent(CenterHandover requested) {
        List<Snapshot> snapshots = jdbc.query("""
                SELECT id, status FROM center_handovers
                WHERE found_item_id = ? AND superseded_at IS NULL
                """, (result, row) -> new Snapshot(result.getLong("id"), result.getString("status")),
                requested.getFoundItemId());
        if (snapshots.size() != 1
                || !snapshots.getFirst().id().equals(requested.getId())
                || !snapshots.getFirst().status().equals(CenterHandoverStatus.USER_CONFIRMED.name())) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
    }

    private record Snapshot(Long id, String status) {
    }
}
