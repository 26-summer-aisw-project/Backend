package kr.lostory.backend.audit.application;

import kr.lostory.backend.audit.domain.AuditLog;
import kr.lostory.backend.audit.domain.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class P0AuditService {

    private static final String FOUND_ITEM = "FOUND_ITEM";
    private static final String LOST_CENTER = "LOST_CENTER";
    private static final String CENTER_PARTNERSHIP = "CENTER_PARTNERSHIP";

    private final AuditLogRepository repository;

    public P0AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void foundItemFinalized(Long userId, Long itemId) {
        record(userId, "FOUND_ITEM_FINALIZED", FOUND_ITEM, itemId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void foundItemImageReplaced(Long userId, Long itemId) {
        record(userId, "FOUND_ITEM_IMAGE_REPLACED", FOUND_ITEM, itemId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void handoverUserConfirmed(Long userId, Long itemId) {
        record(userId, "HANDOVER_USER_CONFIRMED", FOUND_ITEM, itemId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void handoverWithdrawn(Long userId, Long itemId) {
        record(userId, "HANDOVER_WITHDRAWN", FOUND_ITEM, itemId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void centerDirectoryUpdated(Long userId, Long centerId) {
        record(userId, "CENTER_DIRECTORY_UPDATED", LOST_CENTER, centerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void centerDirectoryCreated(Long adminId, Long centerId) {
        record(adminId, "CENTER_DIRECTORY_CREATED", LOST_CENTER, centerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void partnerCenterCreated(Long adminId, Long partnershipId) {
        record(adminId, "PARTNER_CENTER_CREATED", CENTER_PARTNERSHIP, partnershipId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void partnerCenterApproved(Long adminId, Long partnershipId) {
        record(adminId, "PARTNER_CENTER_APPROVED", CENTER_PARTNERSHIP, partnershipId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void partnerManagerActivated(Long managerId, Long partnershipId) {
        record(managerId, "PARTNER_MANAGER_ACTIVATED", CENTER_PARTNERSHIP, partnershipId);
    }

    private void record(Long userId, String action, String targetType, Long targetId) {
        repository.save(new AuditLog(userId, action, targetType, targetId,
                "{\"actionVersion\":1,\"resourceId\":" + targetId + "}"));
    }
}
