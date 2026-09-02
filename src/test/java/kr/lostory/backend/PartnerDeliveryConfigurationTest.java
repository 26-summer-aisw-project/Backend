package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import kr.lostory.backend.config.PartnerConfiguration;
import kr.lostory.backend.config.PartnerDeliveryProperties;
import kr.lostory.backend.partner.application.PartnerActivationDeliveryCipher;
import kr.lostory.backend.partner.domain.PartnerActivationDelivery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PartnerDeliveryConfigurationTest {

    @Test
    void aesGcmRoundTripUsesFreshNonceAndRejectsTamperingWrongAadAndWrongKey() {
        PartnerActivationDeliveryCipher cipher = cipher(new byte[32]);
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        Instant expiry = now.plusSeconds(86400);
        PartnerActivationDelivery first = cipher.encrypt("https://example.test/secret", 10L, 20L, expiry, now);
        PartnerActivationDelivery second = cipher.encrypt("https://example.test/secret", 10L, 20L, expiry, now);

        assertThat(cipher.decrypt(first)).isEqualTo("https://example.test/secret");
        assertThat(first.getNonce()).hasSize(12).isNotEqualTo(second.getNonce());

        byte[] tampered = first.getCiphertext().clone();
        tampered[0] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(delivery(first, 10L, tampered)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(delivery(first, 11L, first.getCiphertext())))
                .isInstanceOf(IllegalStateException.class);
        byte[] wrongKey = new byte[32];
        wrongKey[0] = 1;
        PartnerActivationDeliveryCipher wrongCipher = cipher(wrongKey);
        assertThatThrownBy(() -> wrongCipher.decrypt(first)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingMalformedAndWrongLengthKeysFailClosed() {
        assertThatThrownBy(() -> new PartnerDeliveryProperties(null, "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartnerDeliveryProperties("not-base64", "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartnerDeliveryProperties(
                Base64.getEncoder().encodeToString(new byte[31]), "v1"))
                .isInstanceOf(IllegalArgumentException.class);
        new ApplicationContextRunner().withUserConfiguration(PartnerConfiguration.class)
                .withPropertyValues("partner.activation-base-url=https://example.test/activate")
                .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(PartnerConfiguration.class)
                .withPropertyValues(
                        "partner.activation-base-url=https://example.test/activate",
                        "partner.delivery.encryption-key=not-base64",
                        "partner.delivery.key-version=v1")
                .run(context -> assertThat(context).hasFailed());
    }

    private PartnerActivationDeliveryCipher cipher(byte[] key) {
        return new PartnerActivationDeliveryCipher(new PartnerDeliveryProperties(
                Base64.getEncoder().encodeToString(key), "test-v1"), new SecureRandom());
    }

    private PartnerActivationDelivery delivery(PartnerActivationDelivery source, Long partnershipId,
            byte[] ciphertext) {
        return new PartnerActivationDelivery(partnershipId, source.getActivationTokenId(), ciphertext,
                source.getNonce(), source.getKeyVersion(), source.getExpiresAt(), source.getCreatedAt());
    }
}
