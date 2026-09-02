package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.founditem.application.FoundItemService;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.founditem.presentation.FinalizeFoundItemRegistrationRequest;
import kr.lostory.backend.lostcenter.application.LostCenterService;
import kr.lostory.backend.lostcenter.presentation.CenterLocationRequest;
import kr.lostory.backend.lostcenter.presentation.CreateLostCenterRequest;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import({
        PostgresTestContainerConfig.class,
        FoundItemDraftApiIntegrationTest.FakeBoundaryConfig.class,
        P0AuditPrivacyIntegrationTest.RollbackConfig.class
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P0AuditPrivacyIntegrationTest {

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
    private static final byte[] REPLACEMENT_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 2};
    private static final String PRIVATE_FILENAME = "private-finder-file.png";
    private static final String PRIVATE_STORAGE = "private-storage-description";
    private static final List<String> FORBIDDEN_FIELDS = List.of(
            "objectKey", "rawAi", "latitude", "longitude", "storageDescription",
            "finderId", "filename", "url", "credential", "secret", "apiKey");

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired FoundItemDraftApiIntegrationTest.FakeObjectStorage storage;
    @Autowired RollbackProbe rollbackProbe;
    @Autowired CenterRollbackProbe centerRollbackProbe;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM match_candidates");
        jdbc.update("DELETE FROM report_waypoints");
        jdbc.update("DELETE FROM lost_reports");
        jdbc.update("DELETE FROM found_item_vision_jobs");
        jdbc.update("DELETE FROM object_deletion_outbox");
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM center_handovers");
        jdbc.update("DELETE FROM found_items");
        jdbc.update("DELETE FROM lost_centers");
        storage.reset();
    }

    @Test
    void allP0MutationsWriteMinimalPrivacySafeAuditThroughRealHttp() throws Exception {
        // Given
        User owner = user(UserRole.USER);
        User admin = user(UserRole.ADMIN);
        String ownerToken = tokens.issue(owner).value();
        String adminToken = tokens.issue(admin).value();
        Long imageItemId = createDraft(ownerToken, PRIVATE_FILENAME);
        Long handoverItemId = createDraft(ownerToken, "handover.png");
        Long handoverCenterId = center("official_verified", true);
        Long adminCenterId = center("admin_verified", false);

        // When
        HttpResponse<String> finalized = patch(imageItemId, ownerToken,
                registration("LEFT_IN_PLACE", null, null));
        HttpResponse<String> replaced = multipart(
                "/api/v1/found-items/" + imageItemId + "/image",
                ownerToken, "PUT", PRIVATE_FILENAME, REPLACEMENT_PNG);
        HttpResponse<String> pending = patch(handoverItemId, ownerToken,
                registration("HANDED_TO_CENTER", handoverCenterId, null));
        HttpResponse<String> confirmed = request("POST",
                "/api/v1/found-items/" + handoverItemId + ":confirm-handover",
                ownerToken, null, false);
        HttpResponse<String> withdrawn = patch(handoverItemId, ownerToken,
                registration("MOVED_TO_SAFE_PLACE", null, PRIVATE_STORAGE));
        HttpResponse<String> centerUpdated = request("PATCH",
                "/api/v1/admin/lost-centers/" + adminCenterId,
                adminToken, "{\"contactPhone\":\"02-1111-2222\"}", true);

        // Then
        assertThat(List.of(finalized, replaced, pending, confirmed, withdrawn, centerUpdated))
                .allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
        List<Map<String, Object>> rows = auditRows();
        assertThat(rows).extracting(row -> row.get("action")).containsExactlyInAnyOrder(
                "FOUND_ITEM_FINALIZED", "FOUND_ITEM_FINALIZED", "FOUND_ITEM_IMAGE_REPLACED",
                "HANDOVER_USER_CONFIRMED", "HANDOVER_WITHDRAWN", "CENTER_DIRECTORY_UPDATED");
        assertThat(rows).allSatisfy(this::assertMinimalMetadata);
        assertThat(rows.stream().filter(row -> row.get("action").equals("CENTER_DIRECTORY_UPDATED")))
                .singleElement().satisfies(row -> assertThat(row.get("user_id")).isEqualTo(admin.getId()));
        assertThat(rows.stream().filter(row -> !row.get("action").equals("CENTER_DIRECTORY_UPDATED")))
                .allSatisfy(row -> assertThat(row.get("user_id")).isEqualTo(owner.getId()));

        String objectKey = jdbc.queryForObject("""
                SELECT object_key FROM found_item_images
                WHERE found_item_id = ? AND is_current ORDER BY id DESC LIMIT 1
                """, String.class, imageItemId);
        String auditJson = rows.toString();
        assertThat(auditJson).doesNotContain(
                objectKey, PRIVATE_FILENAME, PRIVATE_STORAGE, owner.getEmail(),
                "37.5665", "126.9780", "raw-ai-payload", "https://private.invalid", "credential-value");
    }

    @Test
    void existingCenterDirectoryUpdateWritesMinimalAuditThroughRealHttp() throws Exception {
        // Given
        User admin = user(UserRole.ADMIN);
        Long centerId = center("admin_verified", false);

        // When
        HttpResponse<String> response = request("PATCH", "/api/v1/admin/lost-centers/" + centerId,
                tokens.issue(admin).value(), "{\"contactPhone\":\"02-1111-2222\"}", true);

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(auditRows()).singleElement().satisfies(row -> {
            assertThat(row.get("action")).isEqualTo("CENTER_DIRECTORY_UPDATED");
            assertThat(row.get("target_type")).isEqualTo("LOST_CENTER");
            assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(centerId);
            assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(admin.getId());
            assertMinimalMetadata(row);
        });
    }

    @Test
    void adminCenterDirectoryCreateWritesMinimalAuditAndUserCreateWritesNothing() throws Exception {
        // Given
        User admin = user(UserRole.ADMIN);
        User user = user(UserRole.USER);
        String createBody = """
                {"name":"created center","address":"private center address","contactPhone":"02-2222-3333",
                 "location":{"latitude":37.5665,"longitude":126.9780}}
                """;
        String rejectedBody = createBody.replace("created center", "rejected center");

        // When
        HttpResponse<String> created = request("POST", "/api/v1/admin/lost-centers",
                tokens.issue(admin).value(), createBody, true);
        HttpResponse<String> rejected = request("POST", "/api/v1/admin/lost-centers",
                tokens.issue(user).value(), rejectedBody, true);

        // Then
        assertThat(created.statusCode()).isEqualTo(201);
        Long centerId = Long.valueOf(mapper.readTree(created.body()).get("id").asString());
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(mapper.readTree(rejected.body()).get("code").asString()).isEqualTo("COMMON-003");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lost_centers WHERE name = 'rejected center'",
                Integer.class)).isZero();
        assertThat(auditRows()).singleElement().satisfies(row -> {
            assertThat(row.get("action")).isEqualTo("CENTER_DIRECTORY_CREATED");
            assertThat(row.get("target_type")).isEqualTo("LOST_CENTER");
            assertThat(((Number) row.get("target_id")).longValue()).isEqualTo(centerId);
            assertThat(((Number) row.get("user_id")).longValue()).isEqualTo(admin.getId());
            assertMinimalMetadata(row);
        });
    }

    @Test
    void forcedPostCreateExceptionRollsBackCenterAndAudit() {
        // Given
        User admin = user(UserRole.ADMIN);
        CreateLostCenterRequest request = new CreateLostCenterRequest(
                "rolled back center", "private rollback address", "02-3333-4444",
                new CenterLocationRequest(new BigDecimal("37.5665"), new BigDecimal("126.9780")));

        // When / Then
        assertThatThrownBy(() -> centerRollbackProbe.createThenFail(admin.getId(), request))
                .isInstanceOf(ForcedRollback.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lost_centers WHERE name = 'rolled back center'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CENTER_DIRECTORY_CREATED'",
                Integer.class)).isZero();
    }

    @Test
    void failedMutationAndForcedPostMutationExceptionLeaveNoPartialAudit() throws Exception {
        // Given
        User owner = user(UserRole.USER);
        Long itemId = draft(owner.getId());
        FinalizeFoundItemRegistrationRequest request = registrationRequest();

        // When / Then
        assertThatThrownBy(() -> rollbackProbe.finalizeThenFail(itemId, owner.getId(), request))
                .isInstanceOf(ForcedRollback.class);
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id = ?",
                String.class, itemId)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id = ?",
                Integer.class, itemId)).isZero();

        HttpResponse<String> invalid = request("POST",
                "/api/v1/found-items/" + itemId + ":confirm-handover",
                tokens.issue(owner).value(), null, false);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id = ?",
                Integer.class, itemId)).isZero();
    }

    @Test
    void publicFoundItemSerializationRejectsPrivateCandidateAndStorageFields() throws Exception {
        // Given
        User owner = user(UserRole.USER);
        String token = tokens.issue(owner).value();
        Long itemId = createDraft(token, PRIVATE_FILENAME);
        assertThat(patch(itemId, token,
                registration("MOVED_TO_SAFE_PLACE", null, PRIVATE_STORAGE)).statusCode()).isEqualTo(200);

        // When
        HttpResponse<String> response = request("GET", "/api/v1/found-items/" + itemId,
                token, null, false);

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "id", "status", "handoverStatus", "visionStatus", "visionSuggestion", "draftExpiresAt");
        assertThat(response.body()).doesNotContain(PRIVATE_FILENAME, PRIVATE_STORAGE, owner.getEmail(),
                "objectKey", "rawAi", "latitude", "longitude", "storageDescription", "finderId",
                "filename", "url", "credential", "apiKey");
    }

    private void assertMinimalMetadata(Map<String, Object> row) {
        JsonNode metadata = mapper.readTree(row.get("metadata").toString());
        assertThat(metadata.propertyNames()).containsExactlyInAnyOrder("actionVersion", "resourceId");
        assertThat(metadata.get("actionVersion").asInt()).isOne();
        assertThat(metadata.get("resourceId").asLong()).isEqualTo(((Number) row.get("target_id")).longValue());
        assertThat(row.get("target_type")).isIn("FOUND_ITEM", "LOST_CENTER");
        FORBIDDEN_FIELDS.forEach(field -> assertThat(metadata.has(field)).isFalse());
    }

    private List<Map<String, Object>> auditRows() {
        return jdbc.queryForList("""
                SELECT action, target_type, target_id, user_id, metadata_json::text AS metadata
                FROM audit_logs ORDER BY id
                """);
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@task10.example", "hash", "Task 10", role));
    }

    private Long createDraft(String token, String filename) throws Exception {
        HttpResponse<String> response = multipart(
                "/api/v1/found-items/drafts", token, "POST", filename, PNG);
        assertThat(response.statusCode()).isEqualTo(201);
        return Long.valueOf(mapper.readTree(response.body()).get("id").asString());
    }

    private Long draft(Long ownerId) {
        return jdbc.queryForObject("""
                INSERT INTO found_items
                    (finder_id, status, vision_status, handover_status, analysis_generation,
                     created_at, updated_at, draft_expires_at)
                VALUES (?, 'DRAFT', 'READY', 'NONE', 1,
                        clock_timestamp(), clock_timestamp(), clock_timestamp() + INTERVAL '24 hours')
                RETURNING id
                """, Long.class, ownerId);
    }

    private Long center(String verificationStatus, boolean csvManaged) {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, 'Task 10 center', 'private center address',
                        ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
                        '02-0000-0000', '09-18', ?, true, ?,
                        clock_timestamp(), clock_timestamp()) RETURNING id
                """, Long.class, "task10-" + UUID.randomUUID(), verificationStatus, csvManaged);
    }

    private String registration(String method, Long centerId, String storageDescription) {
        String center = centerId == null ? "null" : "\"" + centerId + "\"";
        String storage = storageDescription == null ? "null" : "\"" + storageDescription + "\"";
        return """
                {"category":"WALLET","foundAt":"2026-08-23T08:00:00Z",
                 "foundLocation":{"latitude":37.5665,"longitude":126.9780},
                 "confirmedFeatures":{"color":"BLACK","publicDescription":"black wallet"},
                 "storageMethod":"%s","centerId":%s,"storageDescription":%s}
                """.formatted(method, center, storage);
    }

    private FinalizeFoundItemRegistrationRequest registrationRequest() {
        return new FinalizeFoundItemRegistrationRequest(
                "WALLET",
                Instant.parse("2026-08-23T08:00:00Z"),
                new FinalizeFoundItemRegistrationRequest.FoundLocation(
                        new BigDecimal("37.5665"), new BigDecimal("126.9780")),
                new FinalizeFoundItemRegistrationRequest.ConfirmedFeatures("BLACK", "black wallet"),
                StorageMethod.LEFT_IN_PLACE,
                null,
                null,
                null);
    }

    private HttpResponse<String> patch(Long itemId, String token, String body) throws Exception {
        return request("PATCH", "/api/v1/found-items/" + itemId + "/registration", token, body, true);
    }

    private HttpResponse<String> request(
            String method,
            String path,
            String token,
            String body,
            boolean json
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token);
        if (json) {
            request.header("Content-Type", "application/json");
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> multipart(
            String path,
            String token,
            String method,
            String filename,
            byte[] bytes
    ) throws Exception {
        String boundary = "task10-" + UUID.randomUUID();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; "
                + "filename=\"" + filename + "\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    @TestConfiguration
    static class RollbackConfig {
        @Bean
        RollbackProbe rollbackProbe(FoundItemService service) {
            return new RollbackProbe(service);
        }

        @Bean
        CenterRollbackProbe centerRollbackProbe(LostCenterService service) {
            return new CenterRollbackProbe(service);
        }
    }

    static class RollbackProbe {
        private final FoundItemService service;

        RollbackProbe(FoundItemService service) {
            this.service = service;
        }

        @Transactional
        public void finalizeThenFail(
                Long itemId,
                Long userId,
                FinalizeFoundItemRegistrationRequest request
        ) {
            service.finalizeRegistration(itemId, userId, request);
            throw new ForcedRollback();
        }
    }

    static class CenterRollbackProbe {
        private final LostCenterService service;

        CenterRollbackProbe(LostCenterService service) {
            this.service = service;
        }

        @Transactional
        public void createThenFail(Long adminId, CreateLostCenterRequest request) {
            service.create(adminId, request);
            throw new ForcedRollback();
        }
    }

    static class ForcedRollback extends RuntimeException {
    }
}
