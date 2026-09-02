package kr.lostory.backend.partner.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CenterActivationTokenRepository extends JpaRepository<CenterActivationToken, Long> {

    @Query("select token.partnershipId from CenterActivationToken token where token.tokenHash = :hash")
    Optional<Long> findPartnershipIdByTokenHash(@Param("hash") byte[] hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token from CenterActivationToken token
            where token.partnershipId = :partnershipId
              and token.consumedAt is null
              and token.replaced = false
            """)
    Optional<CenterActivationToken> findCurrentForUpdate(@Param("partnershipId") Long partnershipId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select token from CenterActivationToken token
            where token.partnershipId = :partnershipId and token.tokenHash = :hash
            """)
    Optional<CenterActivationToken> findByHashForUpdate(
            @Param("partnershipId") Long partnershipId,
            @Param("hash") byte[] hash);
}
