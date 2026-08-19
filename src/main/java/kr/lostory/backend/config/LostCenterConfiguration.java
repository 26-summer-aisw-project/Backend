package kr.lostory.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LostCenterProperties.class)
public class LostCenterConfiguration {
}
