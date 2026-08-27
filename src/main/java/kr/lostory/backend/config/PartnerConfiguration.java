package kr.lostory.backend.config;

import java.security.SecureRandom;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PartnerProperties.class)
public class PartnerConfiguration {

    @Bean
    SecureRandom partnerSecureRandom() {
        return new SecureRandom();
    }
}
