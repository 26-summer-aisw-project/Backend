package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import kr.lostory.backend.config.PartnerDeliveryProperties;
import kr.lostory.backend.partner.application.PartnerActivationDeliveryCipher;
import kr.lostory.backend.partner.domain.PartnerActivationDelivery;
import org.junit.jupiter.api.Test;

class PartnerActivationDeliveryCipherTimestampPrecisionTest {

    @Test
    void decryptsDeliveryReloadedAfterPostgresRoundsExpiryIntoNextSecond() {
        // Given
        PartnerActivationDeliveryCipher cipher = new PartnerActivationDeliveryCipher(
                new PartnerDeliveryProperties(Base64.getEncoder().encodeToString(new byte[32]), "test-v1"),
                new SecureRandom());
        Instant expiresAt = Instant.parse("2026-08-29T00:00:00.999999789Z");
        PartnerActivationDelivery encrypted = cipher.encrypt("https://example.test/activate", 10L, 20L,
                expiresAt, Instant.parse("2026-08-28T00:00:00Z"));
        PartnerActivationDelivery reloaded = new PartnerActivationDelivery(encrypted.getPartnershipId(),
                encrypted.getActivationTokenId(), encrypted.getCiphertext(), encrypted.getNonce(),
                encrypted.getKeyVersion(), Instant.parse("2026-08-29T00:00:01Z"), encrypted.getCreatedAt());

        // When
        String decrypted = cipher.decrypt(reloaded);

        // Then
        assertThat(decrypted).isEqualTo("https://example.test/activate");
    }
}
