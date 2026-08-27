package kr.lostory.backend.partner.presentation;

import java.time.Instant;

public final class PartnerCenterResponses {

    private PartnerCenterResponses() {
    }

    public record Created(String partnershipId, String centerId, String status, String managerEmail) {
    }

    public record Approved(String partnershipId, String status, String activationUrl, Instant expiresAt) {
    }

    public record Activated(String partnershipId, String centerId, String managerUserId, String status) {
    }
}
