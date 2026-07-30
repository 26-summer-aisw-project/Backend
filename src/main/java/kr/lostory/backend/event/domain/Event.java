package kr.lostory.backend.event.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Instant; //UTC 기준의 특정 시점, LocalDateTime은 시간대가 없음
import kr.lostory.backend.organization.domain.Organization;

@Entity
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY) //event 가져올 시 organization은 가져오지 않게 함
    @JoinColumn(name = "organization_id") //매핑용
    private Organization organization;

    private String name;

    private Instant startsAt;

    private Instant endsAt;

    private String address;

    private String contactPhone;

    private String guideline;

    private String transferPolicy;

    @Enumerated(EnumType.STRING)
    private EventStatus status;

    private String storageLocation;

    private Instant createdAt;

    protected Event() {
        //JPA 기본 생성자
    }

    public Event(
            Organization organization,
            String name,
            Instant startsAt,
            Instant endsAt,
            String address,
            String contactPhone,
            String guideline,
            String transferPolicy,
            String storageLocation
    ) {
        //도메인 규칙
        this.organization = organization;
        this.name = name;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.address = address;
        this.contactPhone = contactPhone;
        this.guideline = guideline;
        this.transferPolicy = transferPolicy;
        this.storageLocation = storageLocation;
        this.status = EventStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

}