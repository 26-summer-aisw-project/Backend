package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.application.MatchingFeatureResolver;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundItemRegistrationApiIntegrationTest {

    private static final byte[] PNG = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
    private static final byte[] SECOND_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 2};

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired FoundItemRepository items;
    @Autowired JdbcTemplate jdbc;
    @Autowired FoundItemDraftApiIntegrationTest.FakeObjectStorage storage;
    @Autowired MatchingFeatureResolver featureResolver;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM match_candidates");
        jdbc.update("DELETE FROM report_waypoints");
        jdbc.update("DELETE FROM lost_reports");
        jdbc.update("DELETE FROM found_item_vision_jobs");
        jdbc.update("DELETE FROM object_deletion_outbox");
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM found_items");
        jdbc.update("DELETE FROM lost_centers");
        storage.reset();
    }

    @Test
    void draftFinalizesToActiveLeftAndPendingCenterThroughRealHttp() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String leftId = createDraft(token);
        String centerId = insertEligibleCenter();
        String handoverId = createDraft(token);
        long openReport = insertReport(owner.getId(), "OPEN");

        // When
        HttpResponse<String> left = patch(leftId, token, registration("LEFT_IN_PLACE", null, null));
        HttpResponse<String> handed = patch(handoverId, token,
                registration("HANDED_TO_CENTER", centerId, null));

        // Then
        assertThat(left.statusCode()).isEqualTo(200);
        JsonNode leftJson = mapper.readTree(left.body());
        assertThat(leftJson.get("id").asString()).isEqualTo(leftId);
        assertThat(leftJson.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(leftJson.get("storageMethod").asString()).isEqualTo("LEFT_IN_PLACE");
        assertThat(leftJson.get("handoverStatus").asString()).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT draft_expires_at IS NULL FROM found_items WHERE id = ?",
                Boolean.class, Long.valueOf(leftId))).isTrue();
        assertThat(jdbc.queryForObject("SELECT expired_at IS NOT NULL FROM found_items WHERE id = ?",
                Boolean.class, Long.valueOf(leftId))).isTrue();
        assertFinderFeatures(leftId, "BLACK", "검은 카드 지갑");
        assertThat(stale(openReport)).isTrue();

        assertThat(handed.statusCode()).isEqualTo(200);
        JsonNode handedJson = mapper.readTree(handed.body());
        assertThat(handedJson.get("status").asString()).isEqualTo("PENDING_HANDOVER");
        assertThat(handedJson.get("centerId").asString()).isEqualTo(centerId);
        assertThat(handedJson.get("handoverStatus").asString()).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT handed_at IS NULL FROM found_items WHERE id = ?",
                Boolean.class, Long.valueOf(handoverId))).isTrue();
        System.out.println("TASK6_HTTP_TRANSITIONS DRAFT->ACTIVE/LEFT DRAFT->PENDING/HANDED_TO_CENTER");
    }

    @Test
    void ownerPutReplacesOnePhotoInvalidatesFeaturesAndMarksOnlyOpenReportsStale() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String id = createDraft(token);
        patch(id, token, registration("LEFT_IN_PLACE", null, null));
        jdbc.update("""
                INSERT INTO item_features
                    (item_id, kind, feature_value, ordinal, source, visibility, confidence, created_at)
                VALUES (?, 'LABEL', 'wallet', 1, 'AI', 'MATCH_ONLY', 0.9, clock_timestamp())
                """, Long.valueOf(id));
        long openReport = insertReport(owner.getId(), "OPEN");
        long closedReport = insertReport(owner.getId(), "CLOSED");
        long expiredReport = insertReport(owner.getId(), "EXPIRED");

        // When
        HttpResponse<String> response = imagePut(id, token, SECOND_PNG);

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT analysis_generation FROM found_items WHERE id = ?",
                Integer.class, Long.valueOf(id))).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM found_item_images WHERE found_item_id = ? AND is_current",
                Integer.class, Long.valueOf(id))).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item_features WHERE item_id = ?",
                Integer.class, Long.valueOf(id))).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM found_item_vision_jobs WHERE found_item_id = ? AND status = 'PENDING'",
                Integer.class, Long.valueOf(id))).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM found_item_vision_jobs WHERE found_item_id = ? AND status = 'SUPERSEDED'",
                Integer.class, Long.valueOf(id))).isOne();
        assertThat(stale(openReport)).isTrue();
        assertThat(stale(closedReport)).isFalse();
        assertThat(stale(expiredReport)).isFalse();
        System.out.println("TASK6_HTTP_REPLACEMENT owner=200 generation=2 current=1 open-stale=true");
    }

    @Test
    void repeatedOwnerImagePutMarksEligibleOpenReportOnceAndNeverRewritesIt() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String id = createDraft(token);
        assertThat(patch(id, token, registration("LEFT_IN_PLACE", null, null)).statusCode()).isEqualTo(200);
        long openReport = insertReport(owner.getId(), "OPEN");
        long closedReport = insertReport(owner.getId(), "CLOSED");
        long expiredReport = insertReport(owner.getId(), "EXPIRED");
        createStaleWriteAudit();

        try {
            // When
            HttpResponse<String> first = imagePut(id, token, SECOND_PNG);
            int firstOpenWrites = staleWriteCount(openReport);
            HttpResponse<String> second = imagePut(id, token, SECOND_PNG);

            // Then
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(firstOpenWrites).isOne();
            assertThat(staleWriteCount(openReport)).isOne();
            assertThat(stale(openReport)).isTrue();
            assertThat(staleWriteCount(closedReport)).isZero();
            assertThat(stale(closedReport)).isFalse();
            assertThat(staleWriteCount(expiredReport)).isZero();
            assertThat(stale(expiredReport)).isFalse();
            System.out.println("TASK6_HTTP_REPEATED_IMAGE_STALE_WRITES first=1 second=0 closed=0 expired=0");
        } finally {
            dropStaleWriteAudit();
        }
    }

    @Test
    void registrationRejectsForeignMalformedAndTerminalMutationWithoutChangingRows() throws Exception {
        // Given
        User owner = user();
        User foreign = user();
        String ownerToken = tokens.issue(owner).value();
        String id = createDraft(ownerToken);

        // When
        HttpResponse<String> foreignResponse = patch(id, tokens.issue(foreign).value(),
                registration("LEFT_IN_PLACE", null, null));
        HttpResponse<String> missingMovedDetail = patch(id, ownerToken,
                registration("MOVED_TO_SAFE_PLACE", null, null));
        HttpResponse<String> unknownColor = patch(id, ownerToken,
                registration("LEFT_IN_PLACE", null, null).replace("BLACK", "ULTRAVIOLET"));
        HttpResponse<String> clientHandedAt = patch(id, ownerToken,
                registration("LEFT_IN_PLACE", null, null).replace(
                        "\"storageMethod\":\"LEFT_IN_PLACE\"",
                        "\"handedAt\":\"2026-08-23T09:00:00Z\",\"storageMethod\":\"LEFT_IN_PLACE\""));
        HttpResponse<String> malformed = patchRaw(id, ownerToken, "{not-json");
        String malformedImageId = createDraft(ownerToken);
        HttpResponse<String> malformedImage = imagePut(malformedImageId, ownerToken, "not-an-image".getBytes());
        HttpResponse<String> foreignImage = imagePut(id, tokens.issue(foreign).value(), SECOND_PNG);
        HttpResponse<String> finalized = patch(id, ownerToken, registration("LEFT_IN_PLACE", null, null));
        jdbc.update("UPDATE found_items SET status = 'EXPIRED' WHERE id = ?", Long.valueOf(id));
        HttpResponse<String> terminal = patch(id, ownerToken, registration("LEFT_IN_PLACE", null, null));
        HttpResponse<String> terminalImage = imagePut(id, ownerToken, SECOND_PNG);

        // Then
        assertError(foreignResponse, 404, "COMMON-004");
        assertError(missingMovedDetail, 400, "COMMON-001");
        assertError(unknownColor, 400, "COMMON-001");
        assertError(clientHandedAt, 400, "COMMON-001");
        assertError(malformed, 400, "COMMON-001");
        assertError(malformedImage, 400, "COMMON-001");
        assertError(foreignImage, 404, "COMMON-004");
        assertThat(finalized.statusCode()).isEqualTo(200);
        assertError(terminal, 400, "COMMON-001");
        assertError(terminalImage, 400, "COMMON-001");
        assertThat(items.findById(Long.valueOf(id)).orElseThrow().getStatus().name()).isEqualTo("EXPIRED");
        System.out.println("TASK6_HTTP_FAILURES foreign=404 malformed=400 terminal=400 mutation=false");
    }

    @Test
    void matchingFeaturesPreferFinderThenUseLowestOrdinalAndIdAiFallback() throws Exception {
        // Given
        User owner = user();
        String id = createDraft(tokens.issue(owner).value());
        jdbc.update("""
                INSERT INTO item_features
                    (item_id, kind, feature_value, ordinal, source, visibility, confidence, created_at)
                VALUES (?, 'COLOR', 'WHITE', 2, 'AI', 'MATCH_ONLY', 0.8, clock_timestamp()),
                       (?, 'COLOR', 'BLACK', 1, 'AI', 'MATCH_ONLY', 0.9, clock_timestamp()),
                       (?, 'COLOR', 'RED', 2, 'FINDER', 'CANDIDATE_VIEW', null, clock_timestamp()),
                       (?, 'COLOR', 'BLUE', 1, 'FINDER', 'CANDIDATE_VIEW', null, clock_timestamp())
                """, Long.valueOf(id), Long.valueOf(id), Long.valueOf(id), Long.valueOf(id));

        // When
        String finderValue = featureResolver.resolve(Long.valueOf(id), ItemFeatureKind.COLOR).orElseThrow();
        jdbc.update("DELETE FROM item_features WHERE item_id = ? AND source = 'FINDER'", Long.valueOf(id));
        String aiValue = featureResolver.resolve(Long.valueOf(id), ItemFeatureKind.COLOR).orElseThrow();

        // Then
        assertThat(finderValue).isEqualTo("BLUE");
        assertThat(aiValue).isEqualTo("BLACK");
        System.out.println("TASK6_FEATURE_PRECEDENCE finder=BLUE ai-fallback=BLACK order=ordinal,id");
    }

    @Test
    void ownerCanMoveAndWithdrawPendingWhileWrongMethodFieldsAreRejected() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String id = createDraft(token);
        String centerId = insertEligibleCenter();
        assertThat(patch(id, token, registration("HANDED_TO_CENTER", centerId, null)).statusCode()).isEqualTo(200);

        // When
        HttpResponse<String> moved = patch(id, token,
                registration("MOVED_TO_SAFE_PLACE", null, "관리실 보관함"));
        HttpResponse<String> wrongCenter = patch(id, token,
                registration("LEFT_IN_PLACE", centerId, null));

        // Then
        JsonNode movedJson = mapper.readTree(moved.body());
        assertThat(moved.statusCode()).isEqualTo(200);
        assertThat(movedJson.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(movedJson.get("storageMethod").asString()).isEqualTo("MOVED_TO_SAFE_PLACE");
        assertThat(movedJson.get("centerId").isNull()).isTrue();
        assertError(wrongCenter, 400, "COMMON-001");
    }

    @Test
    void storageDescriptionAndPendingCenterOnlyEditsDoNotMarkCandidatesStale() throws Exception {
        User owner = user();
        String token = tokens.issue(owner).value();
        String movedId = createDraft(token);
        assertThat(patch(movedId, token,
                registration("MOVED_TO_SAFE_PLACE", null, "first shelf")).statusCode()).isEqualTo(200);
        long storageReport = insertReport(owner.getId(), "OPEN");

        HttpResponse<String> storageOnly = patch(movedId, token,
                registration("MOVED_TO_SAFE_PLACE", null, "second shelf"));

        assertThat(storageOnly.statusCode()).isEqualTo(200);
        assertThat(stale(storageReport)).isFalse();

        String pendingId = createDraft(token);
        String firstCenter = insertEligibleCenter();
        String secondCenter = insertEligibleCenter();
        assertThat(patch(pendingId, token,
                registration("HANDED_TO_CENTER", firstCenter, null)).statusCode()).isEqualTo(200);
        long centerReport = insertReport(owner.getId(), "OPEN");

        HttpResponse<String> centerOnly = patch(pendingId, token,
                registration("HANDED_TO_CENTER", secondCenter, null));

        assertThat(centerOnly.statusCode()).isEqualTo(200);
        assertThat(stale(centerReport)).isFalse();
    }

    @Test
    void eachMatchingInputAndEligibilityTransitionMarksOpenUnexpiredReportsStale() throws Exception {
        User owner = user();
        String token = tokens.issue(owner).value();
        String id = createDraft(token);
        assertThat(patch(id, token, registration("LEFT_IN_PLACE", null, null)).statusCode()).isEqualTo(200);
        long reportId = insertReport(owner.getId(), "OPEN");

        assertThat(patch(id, token, registration("LEFT_IN_PLACE", null, null)
                .replace("WALLET", "BAG")).statusCode()).isEqualTo(200);
        assertThat(stale(reportId)).isTrue();
        jdbc.update("UPDATE lost_reports SET candidates_stale = false WHERE id = ?", reportId);

        assertThat(patch(id, token, registration("LEFT_IN_PLACE", null, null)
                .replace("2026-08-23T08:00:00Z", "2026-08-23T08:01:00Z")
                .replace("WALLET", "BAG")).statusCode()).isEqualTo(200);
        assertThat(stale(reportId)).isTrue();
        jdbc.update("UPDATE lost_reports SET candidates_stale = false WHERE id = ?", reportId);

        assertThat(patch(id, token, registration("LEFT_IN_PLACE", null, null)
                .replace("37.5665", "37.5666")
                .replace("WALLET", "BAG")
                .replace("2026-08-23T08:00:00Z", "2026-08-23T08:01:00Z")).statusCode()).isEqualTo(200);
        assertThat(stale(reportId)).isTrue();
        jdbc.update("UPDATE lost_reports SET candidates_stale = false WHERE id = ?", reportId);

        assertThat(patch(id, token, registration("LEFT_IN_PLACE", null, null)
                .replace("37.5665", "37.5666")
                .replace("WALLET", "BAG")
                .replace("2026-08-23T08:00:00Z", "2026-08-23T08:01:00Z")
                .replace("BLACK", "BLUE")).statusCode()).isEqualTo(200);
        assertThat(stale(reportId)).isTrue();

        jdbc.update("UPDATE lost_reports SET candidates_stale = false WHERE id = ?", reportId);
        String centerId = insertEligibleCenter();
        assertThat(patch(id, token, registration("HANDED_TO_CENTER", centerId, null)
                .replace("37.5665", "37.5666")
                .replace("WALLET", "BAG")
                .replace("2026-08-23T08:00:00Z", "2026-08-23T08:01:00Z")
                .replace("BLACK", "BLUE")).statusCode()).isEqualTo(200);
        assertThat(stale(reportId)).isTrue();
    }

    private User user() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@task6.example", "hash"));
    }

    private String createDraft(String token) throws Exception {
        HttpResponse<String> response = multipart("/api/v1/found-items/drafts", token, "POST", PNG);
        assertThat(response.statusCode()).isEqualTo(201);
        return mapper.readTree(response.body()).get("id").asString();
    }

    private String insertEligibleCenter() {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, '센터', '서울', ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
                        '02-0000-0000', '09-18', 'official_verified', true, true,
                        clock_timestamp(), clock_timestamp())
                RETURNING id
                """, String.class, "task6-" + UUID.randomUUID());
    }

    private long insertReport(Long reporterId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO lost_reports
                    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
                     effective_search_radius_meters, radius_policy_version, center_guidance,
                     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
                VALUES (?, 'WALLET', clock_timestamp() - INTERVAL '1 day', clock_timestamp(), 'wallet',
                        1000, 1000, 'p0-radius-v1', '[]', false, 'p0-matching-v1', ?,
                        clock_timestamp() + INTERVAL '14 days', clock_timestamp(), clock_timestamp())
                RETURNING id
                """, Long.class, reporterId, status);
    }

    private boolean stale(long reportId) {
        return jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
                Boolean.class, reportId);
    }

    private void createStaleWriteAudit() {
        jdbc.execute("CREATE TABLE lost_report_stale_write_audit (report_id BIGINT NOT NULL)");
        jdbc.execute("""
                CREATE FUNCTION record_lost_report_stale_write() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO lost_report_stale_write_audit (report_id) VALUES (NEW.id);
                    RETURN NEW;
                END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER record_lost_report_stale_write
                AFTER UPDATE OF candidates_stale ON lost_reports
                FOR EACH ROW EXECUTE FUNCTION record_lost_report_stale_write()
                """);
    }

    private int staleWriteCount(long reportId) {
        return jdbc.queryForObject("SELECT count(*) FROM lost_report_stale_write_audit WHERE report_id = ?",
                Integer.class, reportId);
    }

    private void dropStaleWriteAudit() {
        jdbc.execute("DROP TRIGGER IF EXISTS record_lost_report_stale_write ON lost_reports");
        jdbc.execute("DROP FUNCTION IF EXISTS record_lost_report_stale_write()");
        jdbc.execute("DROP TABLE IF EXISTS lost_report_stale_write_audit");
    }

    private String registration(String method, String centerId, String storageDescription) {
        String center = centerId == null ? "null" : "\"" + centerId + "\"";
        String detail = storageDescription == null ? "null" : "\"" + storageDescription + "\"";
        return """
                {"category":"WALLET","foundAt":"2026-08-23T08:00:00Z",
                 "foundLocation":{"latitude":37.5665,"longitude":126.9780},
                 "confirmedFeatures":{"color":"BLACK","publicDescription":"검은 카드 지갑"},
                 "storageMethod":"%s","centerId":%s,"storageDescription":%s}
                """.formatted(method, center, detail);
    }

    private HttpResponse<String> patch(String id, String token, String body) throws Exception {
        return patchRaw(id, token, body);
    }

    private HttpResponse<String> patchRaw(String id, String token, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/v1/found-items/" + id + "/registration"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> imagePut(String id, String token, byte[] bytes) throws Exception {
        return multipart("/api/v1/found-items/" + id + "/image", token, "PUT", bytes);
    }

    private HttpResponse<String> multipart(String path, String token, String method, byte[] bytes) throws Exception {
        String boundary = "task6-" + UUID.randomUUID();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; "
                + "filename=\"wallet.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .method(method, HttpRequest.BodyPublishers.ofByteArray(output.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void assertFinderFeatures(String id, String color, String description) {
        assertThat(jdbc.queryForList("""
                SELECT kind || ':' || feature_value
                FROM item_features
                WHERE item_id = ? AND source = 'FINDER' AND visibility = 'CANDIDATE_VIEW'
                ORDER BY kind, ordinal, id
                """, String.class, Long.valueOf(id)))
                .containsExactly("COLOR:" + color, "PUBLIC_DESCRIPTION:" + description);
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code").asString()).isEqualTo(code);
    }
}
