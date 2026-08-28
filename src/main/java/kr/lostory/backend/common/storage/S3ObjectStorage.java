package kr.lostory.backend.common.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class S3ObjectStorage implements ObjectStorage, AutoCloseable {

    private static final String OPERATION_METADATA = "uploadOperationId";
    private static final String CREATED_METADATA = "uploadedAt";

    private final S3Client client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Clock clock;

    public S3ObjectStorage(S3Client client, S3Presigner presigner, String bucket, Clock clock) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.clock = clock;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType, UUID uploadOperationId) {
        try {
            client.putObject(builder -> builder.bucket(bucket).key(key).contentType(contentType)
                            .acl(ObjectCannedACL.PRIVATE)
                            .metadata(Map.of(
                                    OPERATION_METADATA, uploadOperationId.toString(),
                                    CREATED_METADATA, Instant.now().toString())),
                    RequestBody.fromBytes(bytes));
        } catch (SdkException exception) {
            throw new ObjectStorageException("Object put failed.", exception);
        }
    }

    @Override
    public StoredObject get(String key) {
        try {
            ResponseBytes<GetObjectResponse> response = client.getObjectAsBytes(builder -> builder.bucket(bucket).key(key));
            return new StoredObject(response.asByteArray(), response.response().contentType());
        } catch (S3Exception exception) {
            throw new ObjectStorageException("Object get failed.", exception);
        }
    }

    @Override
    public PresignedGet presignGet(String key, Instant expiresAt) {
        Duration duration = Duration.between(clock.instant(), expiresAt);
        if (duration.isZero() || duration.isNegative()) {
            throw new ObjectStorageException("Object read presign expiry is invalid.");
        }
        try {
            var request = GetObjectPresignRequest.builder()
                    .signatureDuration(duration)
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build();
            URI url = URI.create(presigner.presignGetObject(request).url().toString());
            return new PresignedGet(url, expiresAt);
        } catch (SdkException | IllegalArgumentException exception) {
            throw new ObjectStorageException("Object read presign failed.", exception);
        }
    }

    @Override
    public Optional<ObjectMetadata> head(String key) {
        try {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(metadata(key, response));
        } catch (NoSuchKeyException exception) {
            return Optional.empty();
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return Optional.empty();
            }
            throw new ObjectStorageException("Object head failed.", exception);
        }
    }

    @Override
    public void delete(String key) {
        try {
            client.deleteObject(builder -> builder.bucket(bucket).key(key));
        } catch (S3Exception exception) {
            throw new ObjectStorageException("Object delete failed.", exception);
        }
    }

    @Override
    public List<ObjectMetadata> list(String prefix) {
        try {
            return client.listObjectsV2Paginator(builder -> builder.bucket(bucket).prefix(prefix))
                    .contents().stream()
                    .map(object -> head(object.key()))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (S3Exception exception) {
            throw new ObjectStorageException("Object list failed.", exception);
        }
    }

    private ObjectMetadata metadata(String key, HeadObjectResponse response) {
        String operation = response.metadata().get(OPERATION_METADATA.toLowerCase());
        String uploadedAt = response.metadata().get(CREATED_METADATA.toLowerCase());
        return new ObjectMetadata(
                key,
                response.contentType(),
                response.contentLength(),
                operation == null ? null : UUID.fromString(operation),
                uploadedAt == null ? response.lastModified() : Instant.parse(uploadedAt)
        );
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
