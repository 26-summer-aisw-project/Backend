package kr.lostory.backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import org.hibernate.validator.constraints.URL;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("partner")
public record PartnerProperties(
        @NotBlank @URL(protocol = "https") String activationBaseUrl
) {
    @AssertTrue(message = "partner.activation-base-url must be a credential-free HTTPS URL without query or fragment")
    public boolean isActivationBaseUrlSafe() {
        try {
            URI uri = URI.create(activationBaseUrl);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && !activationBaseUrl.endsWith("/");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
