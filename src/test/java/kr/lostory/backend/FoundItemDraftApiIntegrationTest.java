package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.common.storage.ObjectStorageException;
import kr.lostory.backend.founditem.application.VisionJobWorker;
import kr.lostory.backend.founditem.application.VisionProvider;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemVisionJobRepository;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, FoundItemDraftApiIntegrationTest.FakeBoundaryConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundItemDraftApiIntegrationTest {

    private static final byte[] PNG = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired FoundItemRepository items;
    @Autowired FoundItemImageRepository images;
    @Autowired FoundItemVisionJobRepository jobs;
    @Autowired VisionJobWorker worker;
    @Autowired FakeObjectStorage storage;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM found_item_vision_jobs");
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM found_items");
        storage.reset();
    }

    @Test
    void authenticatedImageCreatesPhotoFirstDraftThroughRealHttp() throws Exception {
        // Given
        User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "hash"));
        Instant beforeCreate = Instant.now();
        String boundary = "lostory-" + UUID.randomUUID();
        byte[] body = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"wallet.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] multipart = new byte[body.length + PNG.length + tail.length];
        System.arraycopy(body, 0, multipart, 0, body.length);
        System.arraycopy(PNG, 0, multipart, body.length, PNG.length);
        System.arraycopy(tail, 0, multipart, body.length + PNG.length, tail.length);
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/found-items/drafts"))
                .header("Authorization", "Bearer " + tokens.issue(owner).value())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart)).build();

        // When
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        // Then
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode json = new ObjectMapper().readTree(response.body());
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
                "id", "status", "visionStatus", "draftExpiresAt");
        assertThat(json.get("id").isString()).isTrue();
        assertThat(json.get("status").asString()).isEqualTo("DRAFT");
        assertThat(json.get("visionStatus").asString()).isEqualTo("PENDING");
        assertThat(Instant.parse(json.get("draftExpiresAt").asString()))
                .isBetween(beforeCreate.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
        long id = Long.parseLong(json.get("id").asString());
        assertThat(images.findByFoundItemIdAndCurrentTrue(id)).isPresent();
        assertThat(jobs.countByFoundItemId(id)).isOne();
        assertThat(worker.processNext()).isTrue();

        HttpResponse<String> detail = get("/api/v1/found-items/" + id, tokens.issue(owner).value());
        JsonNode detailJson = new ObjectMapper().readTree(detail.body());
        assertThat(detail.statusCode()).isEqualTo(200);
        assertThat(detailJson.propertyNames()).containsExactlyInAnyOrder(
                "id", "status", "handoverStatus", "visionStatus", "visionSuggestion", "draftExpiresAt");
        assertThat(detailJson.get("handoverStatus").asString()).isEqualTo("NONE");
        assertThat(detailJson.get("visionStatus").asString()).isEqualTo("READY");
        assertThat(detailJson.get("visionSuggestion").propertyNames())
                .containsExactlyInAnyOrder("color", "publicDescription");
        assertThat(detailJson.get("visionSuggestion").get("color").asString()).isEqualTo("BLACK");
        assertThat(detailJson.get("visionSuggestion").get("publicDescription").asString())
                .isEqualTo("BLACK wallet");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM item_features WHERE item_id = ? AND source = 'AI' "
                        + "AND kind = 'PUBLIC_DESCRIPTION'",
                Integer.class, id)).isZero();
        assertSafe(detail.body());
        System.out.println("P0_HTTP_VISION_OBSERVABLE color=BLACK publicDescription=BLACK wallet persisted=false");

        HttpResponse<String> list = get("/api/v1/found-items?page=1&pageSize=20&status=DRAFT",
                tokens.issue(owner).value());
        JsonNode listJson = new ObjectMapper().readTree(list.body());
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(listJson.get("data")).hasSize(1);
        assertThat(listJson.get("data").get(0).get("id").asString()).isEqualTo(Long.toString(id));
        assertThat(listJson.get("meta").get("page").asInt()).isOne();
        assertThat(listJson.get("meta").get("pageSize").asInt()).isEqualTo(20);
        assertThat(listJson.get("meta").get("totalItems").asLong()).isOne();
        assertSafe(list.body());
    }

    @Test
    void ownerListPaginationAndOwnerAdminDetailConcealForeignItems() throws Exception {
        // Given
        User owner = user(UserRole.USER);
        User foreign = user(UserRole.USER);
        User admin = user(UserRole.ADMIN);
        String ownerToken = tokens.issue(owner).value();
        String foreignToken = tokens.issue(foreign).value();
        String adminToken = tokens.issue(admin).value();
        String firstId = create(ownerToken);
        String secondId = create(ownerToken);
        String foreignId = create(foreignToken);

        // When
        HttpResponse<String> page = get("/api/v1/found-items?page=1&pageSize=1&status=DRAFT", ownerToken);
        HttpResponse<String> ownerDetail = get("/api/v1/found-items/" + firstId, ownerToken);
        HttpResponse<String> adminDetail = get("/api/v1/found-items/" + firstId, adminToken);
        HttpResponse<String> foreignDetail = get("/api/v1/found-items/" + firstId, foreignToken);
        HttpResponse<String> defaults = get("/api/v1/found-items", ownerToken);
        jdbc.update("""
                UPDATE found_items
                SET created_at = clock_timestamp() - INTERVAL '25 hours',
                    updated_at = clock_timestamp() - INTERVAL '25 hours',
                    draft_expires_at = clock_timestamp() - INTERVAL '1 hour'
                WHERE id = ?
                """, Long.valueOf(firstId));
        HttpResponse<String> staleDraft = get("/api/v1/found-items/" + firstId, ownerToken);

        // Then
        JsonNode pageJson = new ObjectMapper().readTree(page.body());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(pageJson.get("data")).hasSize(1);
        assertThat(pageJson.get("meta").get("totalItems").asLong()).isEqualTo(2);
        assertThat(pageJson.get("data").toString()).doesNotContain(foreignId);
        assertThat(Set.of(firstId, secondId)).contains(pageJson.get("data").get(0).get("id").asString());
        assertThat(ownerDetail.statusCode()).isEqualTo(200);
        assertThat(adminDetail.statusCode()).isEqualTo(200);
        assertError(foreignDetail, 404, "COMMON-004");
        JsonNode defaultsJson = new ObjectMapper().readTree(defaults.body());
        assertThat(defaultsJson.get("meta").get("page").asInt()).isOne();
        assertThat(defaultsJson.get("meta").get("pageSize").asInt()).isEqualTo(20);
        assertError(staleDraft, 404, "COMMON-004");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_items WHERE id = ?",
                Integer.class, Long.valueOf(firstId))).isZero();
        assertSafe(ownerDetail.body());
        assertSafe(adminDetail.body());
        assertSafe(foreignDetail.body());
    }

    @Test
    void legacyHandoverStatusIsProjectedAsNoneAcrossOwnerAndAdminHttpResponses() throws Exception {
        // Given
        User owner = user(UserRole.USER);
        User admin = user(UserRole.ADMIN);
        User foreign = user(UserRole.USER);
        Long itemId = jdbc.queryForObject("""
                INSERT INTO found_items (
                    finder_id, name, category, description, found_at, storage_method,
                    legacy_handover_place_name, handover_status, status, vision_status,
                    analysis_generation, created_at, updated_at, expired_at
                ) VALUES (?, 'legacy wallet', 'WALLET', 'legacy handover fixture', clock_timestamp(),
                    'HANDED_TO_CENTER', 'Legacy handover desk', 'LEGACY_UNVERIFIED', 'ACTIVE', 'FAILED',
                    0, clock_timestamp(), clock_timestamp(), clock_timestamp() + INTERVAL '14 days')
                RETURNING id
                """, Long.class, owner.getId());
        String ownerToken = tokens.issue(owner).value();
        String adminToken = tokens.issue(admin).value();
        String foreignToken = tokens.issue(foreign).value();

        // When
        HttpResponse<String> ownerList = get("/api/v1/found-items", ownerToken);
        HttpResponse<String> ownerDetail = get("/api/v1/found-items/" + itemId, ownerToken);
        HttpResponse<String> adminDetail = get("/api/v1/found-items/" + itemId, adminToken);
        HttpResponse<String> foreignDetail = get("/api/v1/found-items/" + itemId, foreignToken);

        // Then
        JsonNode ownerListJson = new ObjectMapper().readTree(ownerList.body());
        JsonNode ownerDetailJson = new ObjectMapper().readTree(ownerDetail.body());
        JsonNode adminDetailJson = new ObjectMapper().readTree(adminDetail.body());
        assertThat(jdbc.queryForObject("SELECT handover_status FROM found_items WHERE id = ?", String.class, itemId))
                .isEqualTo("LEGACY_UNVERIFIED");
        assertThat(ownerList.statusCode()).isEqualTo(200);
        assertThat(ownerListJson.get("data").get(0).get("handoverStatus").asString()).isEqualTo("NONE");
        assertThat(ownerDetail.statusCode()).isEqualTo(200);
        assertThat(ownerDetailJson.has("handoverStatus")).isTrue();
        assertThat(ownerDetailJson.get("handoverStatus").asString()).isEqualTo("NONE");
        assertThat(adminDetail.statusCode()).isEqualTo(200);
        assertThat(adminDetailJson.has("handoverStatus")).isTrue();
        assertThat(adminDetailJson.get("handoverStatus").asString()).isEqualTo("NONE");
        assertThat(ownerList.body()).doesNotContain("LEGACY_UNVERIFIED");
        assertThat(ownerDetail.body()).doesNotContain("LEGACY_UNVERIFIED");
        assertThat(adminDetail.body()).doesNotContain("LEGACY_UNVERIFIED");
        assertError(foreignDetail, 404, "COMMON-004");
        System.out.println("P0_HTTP_LEGACY_HANDOVER_OBSERVABLE internal=LEGACY_UNVERIFIED "
                + "owner-list=NONE owner-detail=NONE admin-detail=NONE foreign=404");
    }

    @Test
    void ownerVisionSuggestionUsesLowestOrdinalsForColorOnlyLabelOnlyAndNeither() throws Exception {
        // Given
        User owner = user(UserRole.USER);
        String token = tokens.issue(owner).value();
        String colorOnlyId = create(token);
        String labelOnlyId = create(token);
        String neitherId = create(token);
        jdbc.update("UPDATE found_items SET vision_status = 'READY' WHERE id IN (?, ?, ?)",
                Long.valueOf(colorOnlyId), Long.valueOf(labelOnlyId), Long.valueOf(neitherId));
        jdbc.update("""
                INSERT INTO item_features
                    (item_id, kind, feature_value, ordinal, source, visibility, confidence)
                VALUES (?, 'COLOR', 'WHITE', 2, 'AI', 'MATCH_ONLY', 0.8),
                       (?, 'COLOR', 'BLACK', 1, 'AI', 'MATCH_ONLY', 0.9),
                       (?, 'LABEL', 'handbag', 2, 'AI', 'MATCH_ONLY', 0.8),
                       (?, 'LABEL', 'wallet', 1, 'AI', 'MATCH_ONLY', 0.9),
                       (?, 'PUBLIC_DESCRIPTION', 'private raw summary', 1, 'AI', 'MATCH_ONLY', 0.9)
                """, Long.valueOf(colorOnlyId), Long.valueOf(colorOnlyId),
                Long.valueOf(labelOnlyId), Long.valueOf(labelOnlyId), Long.valueOf(neitherId));

        // When
        JsonNode colorOnly = new ObjectMapper().readTree(
                get("/api/v1/found-items/" + colorOnlyId, token).body());
        JsonNode labelOnly = new ObjectMapper().readTree(
                get("/api/v1/found-items/" + labelOnlyId, token).body());
        JsonNode neither = new ObjectMapper().readTree(
                get("/api/v1/found-items/" + neitherId, token).body());

        // Then
        assertThat(colorOnly.get("visionSuggestion").get("color").asString()).isEqualTo("BLACK");
        assertThat(colorOnly.get("visionSuggestion").get("publicDescription").asString()).isEqualTo("BLACK");
        assertThat(labelOnly.get("visionSuggestion").get("color").isNull()).isTrue();
        assertThat(labelOnly.get("visionSuggestion").get("publicDescription").asString()).isEqualTo("wallet");
        assertThat(neither.get("visionSuggestion").isNull()).isTrue();
        assertThat(neither.toString()).doesNotContain("private raw summary");
        System.out.println("P0_HTTP_VISION_EDGES_OBSERVABLE color-only=BLACK label-only=wallet neither=null "
                + "private-summary-exposed=false");
    }

    @Test
    void invalidAuthMultipartPagingStatusStorageAndRetiredRoutesUseCommonErrors() throws Exception {
        // Given
        User owner = user(UserRole.USER);
        String token = tokens.issue(owner).value();

        // When
        HttpResponse<String> noToken = multipart("/api/v1/found-items/drafts", null,
                List.of(new Part("image", "wallet.png", "image/png", PNG)));
        HttpResponse<String> missing = multipart("/api/v1/found-items/drafts", token,
                List.of(new Part("other", "wallet.png", "image/png", PNG)));
        HttpResponse<String> duplicate = multipart("/api/v1/found-items/drafts", token, List.of(
                new Part("image", "one.png", "image/png", PNG),
                new Part("image", "two.png", "image/png", PNG)));
        HttpResponse<String> extra = multipart("/api/v1/found-items/drafts", token, List.of(
                new Part("image", "one.png", "image/png", PNG),
                new Part("other", "two.png", "image/png", PNG)));
        HttpResponse<String> mismatchedType = multipart("/api/v1/found-items/drafts", token,
                List.of(new Part("image", "wallet.jpg", "image/jpeg", PNG)));
        HttpResponse<String> invalidPage = get("/api/v1/found-items?page=0", token);
        HttpResponse<String> invalidPageSize = get("/api/v1/found-items?pageSize=101", token);
        HttpResponse<String> invalidStatus = get("/api/v1/found-items?status=UNKNOWN", token);
        storage.failNext();
        HttpResponse<String> storageFailure = multipart("/api/v1/found-items/drafts", token,
                List.of(new Part("image", "wallet.png", "image/png", PNG)));
        HttpResponse<String> retiredCreate = jsonPost("/api/v1/found-items", token, "{}");
        HttpResponse<String> retiredUpload = multipart("/api/v1/found-items/999/images", token,
                List.of(new Part("image", "wallet.png", "image/png", PNG)));
        HttpResponse<String> retiredList = get("/api/v1/found-items/999/images", token);

        // Then
        assertError(noToken, 401, "COMMON-002");
        assertError(missing, 400, "COMMON-001");
        assertError(duplicate, 400, "COMMON-001");
        assertError(extra, 400, "COMMON-001");
        assertError(mismatchedType, 400, "COMMON-001");
        assertError(invalidPage, 400, "COMMON-001");
        assertError(invalidPageSize, 400, "COMMON-001");
        assertError(invalidStatus, 400, "COMMON-001");
        assertError(storageFailure, 500, "COMMON-005");
        assertError(retiredCreate, 404, "COMMON-004");
        assertError(retiredUpload, 404, "COMMON-004");
        assertError(retiredList, 404, "COMMON-004");
        assertThat(items.count()).isZero();
        assertThat(storage.keys()).isEmpty();
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "hash", "User", role));
    }

    private String create(String token) throws Exception {
        HttpResponse<String> response = multipart("/api/v1/found-items/drafts", token,
                List.of(new Part("image", "wallet.png", "image/png", PNG)));
        assertThat(response.statusCode()).isEqualTo(201);
        return new ObjectMapper().readTree(response.body()).get("id").asString();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> jsonPost(String path, String token, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> multipart(String path, String token, List<Part> parts) throws Exception {
        String boundary = "lostory-" + UUID.randomUUID();
        var output = new java.io.ByteArrayOutputStream();
        for (Part part : parts) {
            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name()
                    + "\"; filename=\"" + part.filename() + "\"\r\nContent-Type: " + part.contentType()
                    + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(part.bytes());
            output.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = new ObjectMapper().readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code").asString()).isEqualTo(code);
    }

    private void assertSafe(String body) {
        assertThat(body).doesNotContain(
                "finderId", "objectKey", "storageKey", "imageBytes", "confidence", "rawLabel");
    }

    private record Part(String name, String filename, String contentType, byte[] bytes) {
    }

    @TestConfiguration
    static class FakeBoundaryConfig {
        @Bean
        @Primary
        FakeObjectStorage fakeObjectStorage() {
            return new FakeObjectStorage();
        }

        @Bean
        @Primary
        VisionProvider fakeVisionProvider() {
            return (bytes, request) -> new VisionProvider.VisionResult(
                    List.of(new VisionProvider.Label("wallet", 0.95)),
                    List.of(new VisionProvider.Color(5, 5, 5, 1, 0.9)));
        }
    }

    static class FakeObjectStorage implements ObjectStorage {
        private final Map<String, Stored> objects = new ConcurrentHashMap<>();
        private final AtomicBoolean failNext = new AtomicBoolean();

        @Override
        public void put(String key, byte[] bytes, String contentType, UUID operationId) {
            if (failNext.compareAndSet(true, false)) throw new ObjectStorageException("fake failure");
            objects.put(key, new Stored(bytes.clone(), contentType, operationId, Instant.now()));
        }

        @Override
        public StoredObject get(String key) {
            Stored stored = objects.get(key);
            if (stored == null) throw new ObjectStorageException("missing");
            return new StoredObject(stored.bytes().clone(), stored.contentType());
        }

        @Override
        public PresignedGet presignGet(String key, Instant expiresAt) {
            return new PresignedGet(java.net.URI.create("https://signed.example.test/private-image"), expiresAt);
        }

        @Override
        public Optional<ObjectMetadata> head(String key) {
            Stored stored = objects.get(key);
            return stored == null ? Optional.empty() : Optional.of(metadata(key, stored));
        }

        @Override
        public void delete(String key) {
            objects.remove(key);
        }

        @Override
        public List<ObjectMetadata> list(String prefix) {
            return objects.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                    .map(entry -> metadata(entry.getKey(), entry.getValue())).toList();
        }

        void failNext() { failNext.set(true); }
        Set<String> keys() { return Set.copyOf(objects.keySet()); }
        void reset() { objects.clear(); failNext.set(false); }

        private ObjectMetadata metadata(String key, Stored stored) {
            return new ObjectMetadata(key, stored.contentType(), stored.bytes().length,
                    stored.operationId(), stored.createdAt());
        }

        private record Stored(byte[] bytes, String contentType, UUID operationId, Instant createdAt) {
        }
    }
}
