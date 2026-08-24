package kr.lostory.backend.config;

import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageAnnotatorSettings;
import java.io.IOException;
import kr.lostory.backend.founditem.application.GoogleCloudVisionProvider;
import kr.lostory.backend.founditem.application.VisionProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class VisionConfig {

    private static final org.threeten.bp.Duration DEADLINE = org.threeten.bp.Duration.ofSeconds(10);

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "vision.enabled", havingValue = "true")
    ImageAnnotatorClient imageAnnotatorClient() throws IOException {
        ImageAnnotatorSettings.Builder settings = ImageAnnotatorSettings.newBuilder()
                .setCredentialsProvider(GoogleCredentials::getApplicationDefault);
        RetrySettings noRetry = settings.batchAnnotateImagesSettings().getRetrySettings().toBuilder()
                .setMaxAttempts(1)
                .setTotalTimeout(DEADLINE)
                .build();
        settings.batchAnnotateImagesSettings().setRetrySettings(noRetry);
        return ImageAnnotatorClient.create(settings.build());
    }

    @Bean
    @ConditionalOnProperty(name = "vision.enabled", havingValue = "true")
    VisionProvider googleCloudVisionProvider(ImageAnnotatorClient client) {
        return new GoogleCloudVisionProvider(client);
    }
}
