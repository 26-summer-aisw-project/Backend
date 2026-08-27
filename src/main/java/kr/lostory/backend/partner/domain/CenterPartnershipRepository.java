package kr.lostory.backend.partner.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CenterPartnershipRepository extends JpaRepository<CenterPartnership, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select partnership from CenterPartnership partnership where partnership.id = :id")
    Optional<CenterPartnership> findByIdForUpdate(@Param("id") Long id);
}
