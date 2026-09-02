package kr.lostory.backend.partner.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import kr.lostory.backend.config.PartnerDeliveryProperties;
import kr.lostory.backend.partner.domain.PartnerActivationDelivery;
import org.springframework.stereotype.Component;

@Component
public class PartnerActivationDeliveryCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final String keyVersion;
    private final SecureRandom random;

    public PartnerActivationDeliveryCipher(PartnerDeliveryProperties properties, SecureRandom partnerSecureRandom) {
        this.key = new SecretKeySpec(properties.encryptionKey(), "AES");
        this.keyVersion = properties.keyVersion();
        this.random = partnerSecureRandom;
    }

    public PartnerActivationDelivery encrypt(String activationUrl, Long partnershipId, Long activationTokenId,
            Instant expiresAt, Instant createdAt) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(partnershipId, activationTokenId, expiresAt, keyVersion));
            byte[] ciphertext = cipher.doFinal(activationUrl.getBytes(StandardCharsets.UTF_8));
            return new PartnerActivationDelivery(partnershipId, activationTokenId, ciphertext, nonce,
                    keyVersion, expiresAt, createdAt);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Partner activation delivery encryption failed", exception);
        }
    }

    public String decrypt(PartnerActivationDelivery delivery) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, delivery.getNonce()));
            cipher.updateAAD(aad(delivery.getPartnershipId(), delivery.getActivationTokenId(),
                    delivery.getExpiresAt(), delivery.getKeyVersion()));
            return new String(cipher.doFinal(delivery.getCiphertext()), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Partner activation delivery decryption failed", exception);
        }
    }

    private static byte[] aad(Long partnershipId, Long activationTokenId, Instant expiresAt, String keyVersion) {
        byte[] version = keyVersion.getBytes(StandardCharsets.UTF_8);
        Instant databaseExpiresAt = expiresAt.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
        return ByteBuffer.allocate(Integer.BYTES + Long.BYTES * 3 + Integer.BYTES * 2 + version.length)
                .putInt(1)
                .putLong(partnershipId)
                .putLong(activationTokenId)
                .putLong(databaseExpiresAt.getEpochSecond())
                .putInt(databaseExpiresAt.getNano())
                .putInt(version.length)
                .put(version)
                .array();
    }
}
