package kr.lostory.backend.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class S3ObjectStorageTest {

    @Test
    void presignGetUsesPrivateGetRequestForExactFiveMinuteExpiry() throws Exception {
        Instant now = Instant.parse("2026-08-27T00:00:00Z");
        Instant expiresAt = now.plus(Duration.ofMinutes(5));
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        PresignedGetObjectRequest signed = mock(PresignedGetObjectRequest.class);
        when(signed.url()).thenReturn(URI.create("https://signed.example.test/private-image").toURL());
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(signed);
        S3ObjectStorage storage = new S3ObjectStorage(
                client, presigner, "private-bucket", Clock.fixed(now, ZoneOffset.UTC));

        ObjectStorage.PresignedGet result = storage.presignGet("found-items/private-object", expiresAt);

        ArgumentCaptor<GetObjectPresignRequest> request = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(presigner).presignGetObject(request.capture());
        assertThat(request.getValue().signatureDuration()).isEqualTo(Duration.ofMinutes(5));
        assertThat(request.getValue().getObjectRequest().bucket()).isEqualTo("private-bucket");
        assertThat(request.getValue().getObjectRequest().key()).isEqualTo("found-items/private-object");
        assertThat(result.expiresAt()).isEqualTo(expiresAt);
    }
}
