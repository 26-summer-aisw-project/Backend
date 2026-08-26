package kr.lostory.backend.config;

import java.time.Clock;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.common.storage.S3ObjectStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration
public class ObjectStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "object-storage.enabled", havingValue = "true")
    ObjectStorage s3ObjectStorage(ObjectStorageProperties properties, Clock clock) {
        var credentials = DefaultCredentialsProvider.create();
        var serviceConfiguration = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyle()).build();
        S3Client client = S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfiguration)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(properties.timeout())
                        .socketTimeout(properties.timeout()))
                .build();
        S3Presigner presigner = S3Presigner.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfiguration)
                .build();
        return new S3ObjectStorage(client, presigner, properties.bucket(), clock);
    }
}
