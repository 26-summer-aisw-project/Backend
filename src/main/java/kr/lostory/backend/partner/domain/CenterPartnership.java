package kr.lostory.backend.partner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "center_partnerships")
public class CenterPartnership {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "center_id", nullable = false)
    private Long centerId;

    @Column(name = "manager_email", nullable = false)
    private String managerEmail;

    @Column(name = "manager_display_name", nullable = false)
    private String managerDisplayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnershipStatus status;

    @Column(name = "manager_user_id")
    private Long managerUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    protected CenterPartnership() {
    }

    public CenterPartnership(Long centerId, String managerEmail, String managerDisplayName, Instant now) {
        this.centerId = centerId;
        this.managerEmail = managerEmail;
        this.managerDisplayName = managerDisplayName;
        this.status = PartnershipStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void awaitActivation(Instant now) {
        status = PartnershipStatus.PENDING_ACTIVATION;
        updatedAt = now;
    }

    public void activate(Long userId, Instant now) {
        status = PartnershipStatus.ACTIVE;
        managerUserId = userId;
        activatedAt = now;
        updatedAt = now;
    }
}
