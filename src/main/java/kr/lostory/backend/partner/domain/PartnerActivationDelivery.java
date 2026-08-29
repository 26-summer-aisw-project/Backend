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
@Table(name = "partner_activation_delivery_outbox")
public class PartnerActivationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "partnership_id", nullable = false)
    private Long partnershipId;

    @Column(name = "activation_token_id", nullable = false)
    private Long activationTokenId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] ciphertext;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] nonce;

    @Column(name = "key_version", nullable = false)
    private String keyVersion;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    protected PartnerActivationDelivery() {
    }

    public PartnerActivationDelivery(Long partnershipId, Long activationTokenId, byte[] ciphertext, byte[] nonce,
            String keyVersion, Instant expiresAt, Instant createdAt) {
        this.partnershipId = partnershipId;
        this.activationTokenId = activationTokenId;
        this.ciphertext = ciphertext.clone();
        this.nonce = nonce.clone();
        this.keyVersion = keyVersion;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
    }

    public void supersede(Instant now) {
        supersededAt = now;
    }

    public byte[] getCiphertext() {
        return ciphertext.clone();
    }

    public byte[] getNonce() {
        return nonce.clone();
    }
}
