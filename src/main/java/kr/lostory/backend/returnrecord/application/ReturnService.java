package kr.lostory.backend.returnrecord.application;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.lostory.backend.audit.application.P0AuditService;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.CenterHandover;
import kr.lostory.backend.founditem.domain.CenterHandoverRepository;
import kr.lostory.backend.founditem.domain.CenterHandoverStatus;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.HandoverStatus;
import kr.lostory.backend.lostreport.domain.LostReport;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import kr.lostory.backend.lostreport.domain.LostReportStatus;
import kr.lostory.backend.lostreport.domain.MatchCandidateRepository;
import kr.lostory.backend.partner.domain.CenterPartnership;
import kr.lostory.backend.partner.domain.CenterPartnershipRepository;
import kr.lostory.backend.partner.domain.PartnershipStatus;
import kr.lostory.backend.point.domain.PointAccount;
import kr.lostory.backend.point.domain.PointAccountRepository;
import kr.lostory.backend.point.domain.PointLedger;
import kr.lostory.backend.point.domain.PointLedgerRepository;
import kr.lostory.backend.point.domain.PointPolicy;
import kr.lostory.backend.returnrecord.domain.ReturnRecord;
import kr.lostory.backend.returnrecord.domain.ReturnRecordRepository;
import kr.lostory.backend.returnrecord.presentation.RecordReturnResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReturnService {

    private final CenterPartnershipRepository partnerships;
    private final MatchCandidateRepository candidates;
    private final FoundItemRepository items;
    private final LostReportRepository reports;
    private final CenterHandoverRepository handovers;
    private final ReturnRecordRepository returns;
    private final PointAccountRepository accounts;
    private final PointLedgerRepository ledger;
    private final P0AuditService audit;
    private final Clock clock;
    private final PointPolicy policy;

    public ReturnService(
            CenterPartnershipRepository partnerships,
            MatchCandidateRepository candidates,
            FoundItemRepository items,
            LostReportRepository reports,
            CenterHandoverRepository handovers,
            ReturnRecordRepository returns,
            PointAccountRepository accounts,
            PointLedgerRepository ledger,
            P0AuditService audit,
            Clock clock,
            PointPolicy policy
    ) {
        this.partnerships = partnerships;
        this.candidates = candidates;
        this.items = items;
        this.reports = reports;
        this.handovers = handovers;
        this.returns = returns;
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
        this.policy = policy;
    }

    @Transactional
    public RecordReturnResponse record(Long managerId, Long itemId, Long reportId) {
        CenterPartnership partnership = partnerships
                .findByManagerUserIdAndStatus(managerId, PartnershipStatus.ACTIVE)
                .orElseThrow(() -> new LostoryException(ErrorCode.FORBIDDEN));
        if (!candidates.existsByReportIdAndItemId(reportId, itemId)
                && !returns.existsByFoundItemIdAndLostReportId(itemId, reportId)) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
        CenterHandover requested = handovers.findByFoundItemIdAndSupersededAtIsNull(itemId)
                .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE));
        if (!requested.getCenterId().equals(partnership.getCenterId())) {
            throw new LostoryException(ErrorCode.FORBIDDEN);
        }

        FoundItem item = items.findByIdForUpdate(itemId)
                .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE));
        boolean replayState = item.getStatus() == FoundItemStatus.RETURNED;
        if (!replayState) {
            validateItem(item, requested, partnership.getCenterId());
            item.markReturned();
        }
        reports.markOpenCandidatesStale();

        LostReport report = reports.findByIdForUpdate(reportId)
                .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE));
        CenterHandover handover = handovers.findByIdForUpdate(requested.getId())
                .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE));
        List<ReturnRecord> collisions = returns.findCollisionsForUpdate(handover.getId(), itemId, reportId);
        PointAccount account = lockOrCreateAccount(item.getFinderId());
        boolean canonicalReplay = collisions.size() == 1
                && collisions.getFirst().isCanonical(handover.getId(), itemId, reportId);

        validateAfterLocks(managerId, partnership, item, report, handover, itemId, reportId, canonicalReplay);
        if (!collisions.isEmpty()) {
            if (canonicalReplay) {
                return RecordReturnResponse.from(collisions.getFirst(), policy.centerConfirmedReturnReward());
            }
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
        if (replayState) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }

        ReturnRecord saved = returns.saveAndFlush(new ReturnRecord(
                handover.getId(), itemId, reportId, item.getFinderId(), partnership.getCenterId(), managerId,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));
        PointLedger reward = PointLedger.centerReturnReward(
                item.getFinderId(), saved.getId(), rewardKey(saved.getId()),
                policy.centerConfirmedReturnReward());
        ledger.save(reward);
        account.apply(reward);
        accounts.flush();
        ledger.flush();
        audit.itemReturned(managerId, saved.getId());
        return RecordReturnResponse.from(saved, policy.centerConfirmedReturnReward());
    }

    private PointAccount lockOrCreateAccount(Long finderId) {
        PointAccount account = accounts.findByUserIdForUpdate(finderId).orElse(null);
        if (account == null) {
            accounts.insertIfAbsent(finderId);
            account = accounts.findByUserIdForUpdate(finderId).orElseThrow();
        }
        return account;
    }

    private void validateItem(FoundItem item, CenterHandover handover, Long centerId) {
        if (item.getStatus() != FoundItemStatus.ACTIVE
                || item.getHandoverStatus() != HandoverStatus.CENTER_CONFIRMED
                || !item.getId().equals(handover.getFoundItemId())
                || !centerId.equals(item.getCenterId())) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
    }

    private void validateAfterLocks(
            Long managerId,
            CenterPartnership partnership,
            FoundItem item,
            LostReport report,
            CenterHandover handover,
            Long itemId,
            Long reportId,
            boolean canonicalReplay
    ) {
        CenterPartnership currentScope = partnerships
                .findByManagerUserIdAndStatus(managerId, PartnershipStatus.ACTIVE)
                .orElseThrow(() -> new LostoryException(ErrorCode.FORBIDDEN));
        if (!managerId.equals(currentScope.getManagerUserId())
                || !partnership.getId().equals(currentScope.getId())
                || !partnership.getCenterId().equals(currentScope.getCenterId())
                || !itemId.equals(item.getId())
                || !reportId.equals(report.getId())) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
        if (!handover.getFoundItemId().equals(itemId)
                || !handover.getCenterId().equals(partnership.getCenterId())
                || handover.getStatus() != CenterHandoverStatus.CENTER_CONFIRMED
                || handover.getSupersededAt() != null
                || item.getHandoverStatus() != HandoverStatus.CENTER_CONFIRMED
                || !item.getCenterId().equals(partnership.getCenterId())) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
        if (canonicalReplay) {
            return;
        }
        Instant now = clock.instant();
        if (report.getStatus() != LostReportStatus.OPEN || !report.getExpiredAt().isAfter(now)) {
            throw new LostoryException(ErrorCode.REPORT_NOT_OPEN);
        }
        if (!candidates.existsByReportIdAndItemId(reportId, itemId)) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
    }

    private UUID rewardKey(Long returnId) {
        return UUID.nameUUIDFromBytes(("center-return:" + returnId).getBytes(StandardCharsets.UTF_8));
    }
}
