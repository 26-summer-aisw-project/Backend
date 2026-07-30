package kr.lostory.backend.organization.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class Organization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private OrganizationType type;

    private String name;

    private String address;

    private String contactPhone;

    private String operatingHours;

    @Enumerated(EnumType.STRING)
    private OrganizationApprovalStatus status;

    private Instant createdAt;

    protected Organization() {
    }

    public Organization(
            OrganizationType type,
            String name,
            String address,
            String contactPhone,
            String operatingHours
    ) {
        this.type = type;
        this.name = name;
        this.address = address;
        this.contactPhone = contactPhone;
        this.operatingHours = operatingHours;
        this.status = OrganizationApprovalStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

}