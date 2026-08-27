package kr.lostory.backend.config;

import kr.lostory.backend.founditem.application.RekognitionVisionProvider;
import kr.lostory.backend.founditem.application.VisionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "vision.provider", havingValue = "rekognition")
@EnableConfigurationProperties(RekognitionProperties.class)
public class RekognitionConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "vision.enabled", havingValue = "true")
    RekognitionClient rekognitionClient(RekognitionProperties properties) {
        return RekognitionClient.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "vision.enabled", havingValue = "true")
    VisionProvider rekognitionVisionProvider(
            RekognitionClient client,
            RekognitionProperties properties
    ) {
        return new RekognitionVisionProvider(
                client,
                properties.minConfidence(),
                properties.maxObjectSuggestions() * 2);
    }
}
