package kr.lostory.backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("partner.delivery")
public final class PartnerDeliveryProperties {

    private final byte[] encryptionKey;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9._-]{1,64}")
    private final String keyVersion;

    public PartnerDeliveryProperties(String encryptionKey, String keyVersion) {
        this.encryptionKey = decodeKey(encryptionKey);
        this.keyVersion = keyVersion;
    }

    public byte[] encryptionKey() {
        return encryptionKey.clone();
    }

    public String keyVersion() {
        return keyVersion;
    }

    private static byte[] decodeKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Partner delivery encryption key is required");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(value)) {
                throw new IllegalArgumentException("Partner delivery encryption key must be strict Base64 for 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Partner delivery encryption key must be strict Base64 for 32 bytes", exception);
        }
    }
}
