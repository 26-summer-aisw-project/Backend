package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.config.ObjectStorageProperties;
import kr.lostory.backend.founditem.application.ObjectDeletionWorker;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@ActiveProfiles({"test", "local"})
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "object-storage.deletion-worker-initial-delay=PT1H",
                "object-storage.orphan-sweep-initial-delay=PT1H"
        }
)
class Task11MinioLocalSurfaceIntegrationTest {

    private static final String HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5n33S5U9P4XQxG1VVDzI7kVxwZKXgOe";
    private static final String BUCKET = "task11-" + UUID.randomUUID();
    private static final String ACCESS_KEY = "task11" + UUID.randomUUID().toString().replace("-", "");
    private static final String SECRET_KEY = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    private static final String PREVIOUS_ACCESS_KEY = System.getProperty("aws.accessKeyId");
    private static final String PREVIOUS_SECRET_KEY = System.getProperty("aws.secretAccessKey");

    static {
        System.setProperty("aws.accessKeyId", ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", SECRET_KEY);
    }

    @Container
    private static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("minio/minio:RELEASE.2025-04-22T22-12-26Z"))
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data", "--address", ":9000")
            .withExposedPorts(9000);

    private static S3Client bootstrapClient;

    @LocalServerPort int port;
    @Autowired ObjectStorage storage;
    @Autowired ObjectStorageProperties storageProperties;
    @Autowired ObjectDeletionWorker deletionWorker;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired FoundItemRepository foundItems;
    @Autowired FoundItemImageRepository images;

    @DynamicPropertySource
    static void minioProperties(DynamicPropertyRegistry properties) {
        properties.add("object-storage.enabled", () -> true);
        properties.add("object-storage.endpoint", Task11MinioLocalSurfaceIntegrationTest::minioEndpoint);
        properties.add("object-storage.bucket", () -> BUCKET);
        properties.add("object-storage.region", () -> "us-east-1");
        properties.add("object-storage.path-style", () -> true);
    }

    @BeforeAll
    static void createPrivateBucket() {
        bootstrapClient = S3Client.builder()
                .endpointOverride(URI.create(minioEndpoint()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
        bootstrapClient.createBucket(builder -> builder.bucket(BUCKET));
    }

    @AfterAll
    static void removePrivateBucketAndRestoreCredentials() {
        if (bootstrapClient != null) {
            bootstrapClient.listObjectsV2Paginator(builder -> builder.bucket(BUCKET)).contents()
                    .forEach(object -> bootstrapClient.deleteObject(
                            builder -> builder.bucket(BUCKET).key(object.key())));
            bootstrapClient.deleteBucket(builder -> builder.bucket(BUCKET));
            bootstrapClient.close();
        }
        restoreSystemProperty("aws.accessKeyId", PREVIOUS_ACCESS_KEY);
        restoreSystemProperty("aws.secretAccessKey", PREVIOUS_SECRET_KEY);
    }

    @Test
    void localProfileUsesPrivateMinioForStorageAndSignedImageReplacement() throws Exception {
        // Given: the local profile owns only environment-backed, credential-free MinIO overrides.
        Properties local = localProfileProperties();
        assertThat(local).containsEntry("object-storage.enabled", "${OBJECT_STORAGE_ENABLED:true}")
                .containsEntry("object-storage.endpoint", "${OBJECT_STORAGE_ENDPOINT}")
                .containsEntry("object-storage.bucket", "${OBJECT_STORAGE_BUCKET}")
                .containsEntry("object-storage.region", "${OBJECT_STORAGE_REGION:us-east-1}")
                .containsEntry("object-storage.path-style", "${OBJECT_STORAGE_PATH_STYLE:true}");
        assertThat(local.stringPropertyNames()).contains(
                "object-storage.enabled", "object-storage.endpoint", "object-storage.bucket",
                "object-storage.region", "object-storage.path-style");
        assertThat(local.stringPropertyNames()).noneMatch(name -> {
            String normalized = name.toLowerCase();
            return normalized.contains("credential") || normalized.contains("access-key")
                    || normalized.contains("secret-key");
        });
        assertThat(storageProperties.readUrlTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(storageProperties.enabled()).isTrue();
        assertThat(storageProperties.endpoint()).hasToString(minioEndpoint());
        assertThat(storageProperties.bucket()).isEqualTo(BUCKET);
        assertThat(storageProperties.pathStyle()).isTrue();

        byte[] directBytes = runtimePng(0x123456);
        String directKey = "direct/" + UUID.randomUUID();
        storage.put(directKey, directBytes, "image/png", UUID.randomUUID());

        // When: the existing S3 adapter reads and presigns the private object against real MinIO.
        ObjectStorage.StoredObject directRead = storage.get(directKey);
        ObjectStorage.PresignedGet directSigned = storage.presignGet(
                directKey, Instant.now().plus(Duration.ofMinutes(5)));
        HttpResponse<byte[]> directSignedRead = signedRead(directSigned.url());
        HttpResponse<byte[]> unsignedRead = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(minioEndpoint() + "/" + BUCKET + "/" + directKey)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());

        // Then: bytes are available only through the signed URL, and direct delete removes the object.
        assertThat(directRead.bytes()).containsExactly(directBytes);
        assertThat(directRead.contentType()).isEqualTo("image/png");
        assertThat(directSignedRead.statusCode()).isEqualTo(200);
        assertThat(directSignedRead.body()).containsExactly(directBytes);
        assertThat(directSignedRead.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(unsignedRead.statusCode() / 100).isNotEqualTo(2);
        storage.delete(directKey);
        assertThat(storage.head(directKey)).isEmpty();

        User owner = users.saveAndFlush(new User(
                "task11-" + UUID.randomUUID() + "@example.test", HASH, "Owner", UserRole.USER));
        FoundItem item = foundItems.saveAndFlush(new FoundItem(
                owner.getId(), "Wallet", "WALLET_CARD", "Runtime fixture", Instant.now(),
                new BigDecimal("37.5"), new BigDecimal("127.0"), "Seoul", "Desk",
                StorageMethod.LEFT_IN_PLACE, null, null));
        String token = tokens.issue(owner).value();
        HttpResponse<String> malformed = replaceViaHttp(item.getId(), token, "not-an-image".getBytes(StandardCharsets.UTF_8));
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(storage.list("found-items/")).isEmpty();

        byte[] firstBytes = runtimePng(0x654321);
        assertThat(replaceViaHttp(item.getId(), token, firstBytes).statusCode()).isEqualTo(200);
        String oldKey = images.findByFoundItemIdAndCurrentTrue(item.getId()).orElseThrow().getObjectKey();
        Instant firstRequestStarted = Instant.now();
        CurlResult firstCurl = curlSignedImage(item.getId(), token);
        Instant firstRequestFinished = Instant.now();
        JsonNode firstJson = assertSignedJson(firstCurl, firstRequestStarted, firstRequestFinished);
        URI staleUrl = URI.create(firstJson.get("url").asString());
        HttpResponse<byte[]> firstImage = signedRead(staleUrl);
        assertThat(firstImage.statusCode()).isEqualTo(200);
        assertThat(firstImage.body()).containsExactly(firstBytes);
        assertThat(firstImage.headers().firstValue("Content-Type")).contains("image/png");

        byte[] replacementBytes = runtimePng(0xabcdef);
        assertThat(replaceViaHttp(item.getId(), token, replacementBytes).statusCode()).isEqualTo(200);
        String currentKey = images.findByFoundItemIdAndCurrentTrue(item.getId()).orElseThrow().getObjectKey();
        assertThat(currentKey).isNotEqualTo(oldKey);
        assertThat(storage.head(currentKey)).isPresent();
        assertThat(deletionWorker.processNext()).isTrue();
        assertThat(storage.head(oldKey)).isEmpty();
        assertThat(signedRead(staleUrl).statusCode() / 100).isNotEqualTo(2);

        Instant replacementStarted = Instant.now();
        CurlResult replacementCurl = curlSignedImage(item.getId(), token);
        Instant replacementFinished = Instant.now();
        JsonNode replacementJson = assertSignedJson(replacementCurl, replacementStarted, replacementFinished);
        URI replacementUrl = URI.create(replacementJson.get("url").asString());
        assertThat(replacementUrl).isNotEqualTo(staleUrl);
        HttpResponse<byte[]> replacementImage = signedRead(replacementUrl);
        assertThat(replacementImage.statusCode()).isEqualTo(200);
        assertThat(replacementImage.body()).containsExactly(replacementBytes);
        assertThat(replacementImage.headers().firstValue("Content-Type")).contains("image/png");

        storage.delete(currentKey);
        assertThat(storage.head(currentKey)).isEmpty();
    }

    private Properties localProfileProperties() throws IOException {
        Properties properties = new Properties();
        try (var input = new ClassPathResource("application-local.properties").getInputStream()) {
            properties.load(input);
        }
        return properties;
    }

    private JsonNode assertSignedJson(CurlResult response, Instant started, Instant finished) throws IOException {
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers()).containsIgnoringCase("Content-Type: application/json");
        assertThat(response.headers()).containsIgnoringCase("Cache-Control: no-store");
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("url", "expiresAt");
        assertThat(body.toString()).doesNotContain(
                "objectKey", "storagePath", "storedFilename", "imageBytes", "rawBytes");
        Instant expiresAt = Instant.parse(body.get("expiresAt").asString());
        assertThat(expiresAt).isBetween(started.plusSeconds(299), finished.plusSeconds(301));
        return body;
    }

    private CurlResult curlSignedImage(long itemId, String token) throws Exception {
        Process process = new ProcessBuilder(
                "curl", "-i", "--max-time", "15",
                "-H", "Authorization: Bearer " + token,
                "http://127.0.0.1:" + port + "/api/v1/found-items/" + itemId + "/image")
                .start();
        String response;
        try {
            response = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            process.getErrorStream().readAllBytes();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertThat(process.exitValue()).isZero();
        int bodyStart = response.lastIndexOf("\r\n\r\n");
        assertThat(bodyStart).isGreaterThan(0);
        String headers = response.substring(0, bodyStart);
        String statusLine = headers.substring(0, headers.indexOf("\r\n"));
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        return new CurlResult(status, headers, response.substring(bodyStart + 4));
    }

    private HttpResponse<String> replaceViaHttp(long itemId, String token, byte[] bytes) throws Exception {
        String boundary = "task11-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; "
                + "filename=\"invalid.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = Arrays.copyOf(prefix, prefix.length + bytes.length + suffix.length);
        System.arraycopy(bytes, 0, body, prefix.length, bytes.length);
        System.arraycopy(suffix, 0, body, prefix.length + bytes.length, suffix.length);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/api/v1/found-items/" + itemId + "/image"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> signedRead(URI url) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(url).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private byte[] runtimePng(int rgb) throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < image.getWidth(); x++) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.setRGB(x, y, rgb + x + y);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertThat(ImageIO.write(image, "png", output)).isTrue();
            return output.toByteArray();
        }
    }

    private static String minioEndpoint() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static void restoreSystemProperty(String name, String previous) {
        if (previous == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previous);
        }
    }

    private record CurlResult(int status, String headers, String body) {
    }
}
