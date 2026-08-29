package kr.lostory.backend.partner.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PartnerActivationDeliveryRepository extends JpaRepository<PartnerActivationDelivery, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select delivery from PartnerActivationDelivery delivery
            where delivery.partnershipId = :partnershipId and delivery.supersededAt is null
            """)
    Optional<PartnerActivationDelivery> findCurrentForUpdate(@Param("partnershipId") Long partnershipId);

    Optional<PartnerActivationDelivery> findByPartnershipIdAndSupersededAtIsNull(Long partnershipId);

    List<PartnerActivationDelivery> findAllByPartnershipIdOrderById(Long partnershipId);
}
