package kr.lostory.backend.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

class S3ObjectStorageTest {

    @Test
    void headReturnsEmptyForNotFoundResponse() {
        // Given
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        doThrow(S3Exception.builder().statusCode(404).message("missing").build())
                .when(client).headObject(any(HeadObjectRequest.class));
        S3ObjectStorage storage = new S3ObjectStorage(
                client, presigner, "private-bucket", Clock.systemUTC());

        // When / Then
        assertThat(storage.head("found-items/missing-object")).isEmpty();
    }

    @Test
    void headReturnsEmptyForNoSuchKeyResponse() {
        // Given
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        doThrow(NoSuchKeyException.builder().message("missing").build())
                .when(client).headObject(any(HeadObjectRequest.class));
        S3ObjectStorage storage = new S3ObjectStorage(
                client, presigner, "private-bucket", Clock.systemUTC());

        // When / Then
        assertThat(storage.head("found-items/missing-object")).isEmpty();
    }

    @Test
    void getNormalizesSdkClientFailureAndRetainsCause() {
        // Given
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        SdkClientException failure = SdkClientException.create("transport");
        doThrow(failure).when(client).getObjectAsBytes(any(Consumer.class));
        S3ObjectStorage storage = new S3ObjectStorage(
                client, presigner, "private-bucket", Clock.systemUTC());

        // When / Then
        assertThatThrownBy(() -> storage.get("found-items/private-object"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("Object get failed.")
                .hasCause(failure);
    }

    @Test
    void headNormalizesSdkClientFailureAndRetainsCause() {
        // Given
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        SdkClientException failure = SdkClientException.create("transport");
        doThrow(failure).when(client).headObject(any(HeadObjectRequest.class));
        S3ObjectStorage storage = new S3ObjectStorage(
                client, presigner, "private-bucket", Clock.systemUTC());

        // When / Then
        assertThatThrownBy(() -> storage.head("found-items/private-object"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("Object head failed.")
                .hasCause(failure);
    }

    @Test
    void deleteNormalizesSdkClientFailureAndRetainsCause() {
        // Given
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        SdkClientException failure = SdkClientException.create("transport");
        doThrow(failure).when(client).deleteObject(any(Consumer.class));
        S3ObjectStorage storage = new S3ObjectStorage(
                client, presigner, "private-bucket", Clock.systemUTC());

        // When / Then
        assertThatThrownBy(() -> storage.delete("found-items/private-object"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("Object delete failed.")
                .hasCause(failure);
    }

    @Test
    void listNormalizesSdkClientFailureAndRetainsCause() {
        // Given
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        SdkClientException failure = SdkClientException.create("transport");
        doThrow(failure).when(client).listObjectsV2Paginator(any(Consumer.class));
        S3ObjectStorage storage = new S3ObjectStorage(
                client, presigner, "private-bucket", Clock.systemUTC());

        // When / Then
        assertThatThrownBy(() -> storage.list("found-items/"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("Object list failed.")
                .hasCause(failure);
    }

    @Test
    void putNormalizesSdkClientFailureAndRetainsCause() {
        // Given
        S3Client client = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        SdkClientException failure = SdkClientException.create("transport");
        doThrow(failure).when(client).putObject(
                any(java.util.function.Consumer.class), any(RequestBody.class));
        S3ObjectStorage storage = new S3ObjectStorage(
                client, presigner, "private-bucket", Clock.systemUTC());

        // When / Then
        assertThatThrownBy(() -> storage.put(
                "found-items/private-object", new byte[]{1}, "image/png", java.util.UUID.randomUUID()))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("Object put failed.")
                .hasCause(failure);
    }

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
