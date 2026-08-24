package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemVisionJobRepository;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, FoundItemDraftApiIntegrationTest.FakeBoundaryConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "vision.daily-job-limit=1")
class VisionDailyAdmissionIntegrationTest {

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired FoundItemRepository items;
    @Autowired FoundItemImageRepository images;
    @Autowired FoundItemVisionJobRepository jobs;
    @Autowired FoundItemDraftApiIntegrationTest.FakeObjectStorage storage;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM found_item_vision_jobs");
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM found_items");
        jdbc.update("DELETE FROM vision_daily_admissions");
        storage.reset();
    }

    @Test
    void secondValidDraftAcrossAccountsGetsSafe429BeforeObjectAndJobCreation() throws Exception {
        // Given
        String firstToken = token();
        String secondToken = token();

        // When
        HttpResponse<String> admitted = createDraft(firstToken);
        HttpResponse<String> rejected = createDraft(secondToken);

        // Then
        assertThat(admitted.statusCode()).isEqualTo(201);
        assertError(rejected, 429, "VISION-001");
        assertThat(items.count()).isOne();
        assertThat(images.count()).isOne();
        assertThat(jobs.count()).isOne();
        assertThat(storage.keys()).hasSize(1);
        assertThat(reservationCount()).isOne();
    }

    @Test
    void storageFailureAfterAdmissionReleasesCapacityForNextRequest() throws Exception {
        // Given
        String token = token();
        storage.failNext();

        // When
        HttpResponse<String> failed = createDraft(token);
        HttpResponse<String> retried = createDraft(token);

        // Then
        assertError(failed, 500, "COMMON-005");
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(items.count()).isOne();
        assertThat(images.count()).isOne();
        assertThat(jobs.count()).isOne();
        assertThat(storage.keys()).hasSize(1);
        assertThat(reservationCount()).isOne();
    }

    @Test
    void persistenceFailureAfterStorageReleasesCapacityForNextRequest() throws Exception {
        // Given
        String token = token();
        jdbc.execute("""
                CREATE FUNCTION fail_admitted_image_insert() RETURNS trigger LANGUAGE plpgsql AS
                'BEGIN RAISE EXCEPTION ''forced persistence failure''; END'
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_admitted_image_insert
                BEFORE INSERT ON found_item_images
                FOR EACH ROW EXECUTE FUNCTION fail_admitted_image_insert()
                """);

        // When
        HttpResponse<String> failed;
        try {
            failed = createDraft(token);
        } finally {
            jdbc.execute("DROP TRIGGER fail_admitted_image_insert ON found_item_images");
            jdbc.execute("DROP FUNCTION fail_admitted_image_insert()");
        }
        HttpResponse<String> retried = createDraft(token);

        // Then
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(retried.statusCode()).isEqualTo(201);
        assertThat(items.count()).isOne();
        assertThat(images.count()).isOne();
        assertThat(jobs.count()).isOne();
        assertThat(storage.keys()).hasSize(1);
        assertThat(reservationCount()).isOne();
    }

    @Test
    void concurrentRequestsCannotReserveBeyondConfiguredGlobalLimit() throws Exception {
        // Given
        List<String> requestTokens = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            requestTokens.add(token());
        }
        CountDownLatch start = new CountDownLatch(1);

        // When
        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(requestTokens.size())) {
            List<Future<HttpResponse<String>>> futures = requestTokens.stream()
                    .map(requestToken -> executor.submit(() -> {
                        start.await();
                        return createDraft(requestToken);
                    }))
                    .toList();
            start.countDown();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
        }

        // Then
        assertThat(responses).extracting(HttpResponse::statusCode)
                .containsOnly(201, 429)
                .filteredOn(status -> status == 201).hasSize(1);
        assertThat(responses).extracting(HttpResponse::statusCode)
                .filteredOn(status -> status == 429).hasSize(7);
        assertThat(items.count()).isOne();
        assertThat(images.count()).isOne();
        assertThat(jobs.count()).isOne();
        assertThat(storage.keys()).hasSize(1);
        assertThat(reservationCount()).isOne();
    }

    private String token() {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "hash"));
        return tokens.issue(user).value();
    }

    private HttpResponse<String> createDraft(String token) throws Exception {
        String boundary = "admission-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; "
                + "filename=\"wallet.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(PNG);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/found-items/drafts"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private long reservationCount() {
        return jdbc.queryForObject(
                "SELECT COALESCE(sum(reserved_count), 0) FROM vision_daily_admissions",
                Long.class);
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code").asString()).isEqualTo(code);
        String expectedMessage = code.equals("VISION-001")
                ? "Vision processing capacity is unavailable."
                : "An unexpected server error occurred.";
        assertThat(body.get("message").asString()).isEqualTo(expectedMessage);
    }
}
