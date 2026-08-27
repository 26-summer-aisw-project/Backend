package kr.lostory.backend.partner.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

@Getter
@Entity
@Table(name = "center_activation_tokens")
public class CenterActivationToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "partnership_id", nullable = false)
    private Long partnershipId;

    @Column(name = "token_hash", nullable = false, columnDefinition = "bytea")
    private byte[] tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(nullable = false)
    private boolean replaced;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    protected CenterActivationToken() {
    }

    public CenterActivationToken(Long partnershipId, byte[] tokenHash, Instant issuedAt, Instant expiresAt) {
        this.partnershipId = partnershipId;
        this.tokenHash = tokenHash.clone();
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public void replace() {
        replaced = true;
    }

    public void consume(Instant now) {
        consumedAt = now;
    }
}
