package kr.lostory.backend.config;

import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import java.io.IOException;
import java.time.Duration;
import kr.lostory.backend.founditem.application.GoogleCloudVisionProvider;
import kr.lostory.backend.founditem.application.VisionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VisionConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "vision.enabled", havingValue = "true")
    ImageAnnotatorClient imageAnnotatorClient(VisionProperties properties) throws IOException {
        ImageAnnotatorSettings.Builder settings = ImageAnnotatorSettings.newBuilder()
                .setCredentialsProvider(GoogleCredentials::getApplicationDefault);
        org.threeten.bp.Duration timeout = toThreeTen(properties.timeout());
        RetrySettings noRetry = settings.batchAnnotateImagesSettings().getRetrySettings().toBuilder()
                .setMaxAttempts(1)
                .setInitialRpcTimeout(timeout)
                .setMaxRpcTimeout(timeout)
                .setTotalTimeout(timeout)
                .build();
        settings.batchAnnotateImagesSettings().setRetrySettings(noRetry);
        return ImageAnnotatorClient.create(settings.build());
    }

    private org.threeten.bp.Duration toThreeTen(Duration timeout) {
        return org.threeten.bp.Duration.ofSeconds(timeout.getSeconds(), timeout.getNano());
    }

    @Bean
    @ConditionalOnProperty(name = "vision.enabled", havingValue = "true")
    VisionProvider googleCloudVisionProvider(ImageAnnotatorClient client) {
        return new GoogleCloudVisionProvider(client);
    }
}
