package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

    @Test
    void dueRegistrationAndDetailApplyLifecycleBeforeAdmission() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String dueDraftRegistration = createDraft(token);
        String dueDraftDetail = createDraft(token);
        String dueActive = createDraft(token);
        String duePending = createDraft(token);
        String centerId = insertEligibleCenter();
        long draftCandidateReport = insertReport(owner.getId(), "OPEN");
        jdbc.update("""
                INSERT INTO match_candidates
                    (report_id, item_id, rank, score, score_breakdown, created_at)
                VALUES (?, ?, 1, 90, '{}', clock_timestamp())
                """, draftCandidateReport, Long.valueOf(dueDraftRegistration));
        assertThat(patch(dueActive, token, registration("LEFT_IN_PLACE", null, null)).statusCode())
                .isEqualTo(200);
        assertThat(patch(duePending, token, registration("HANDED_TO_CENTER", centerId, null)).statusCode())
                .isEqualTo(200);
        jdbc.update("""
                UPDATE found_items
                SET created_at = clock_timestamp() - INTERVAL '25 hours',
                    updated_at = clock_timestamp() - INTERVAL '25 hours',
                    draft_expires_at = clock_timestamp() - INTERVAL '1 second'
                WHERE id IN (?, ?)
                """, Long.valueOf(dueDraftRegistration), Long.valueOf(dueDraftDetail));
        jdbc.update("""
                UPDATE found_items
                SET updated_at = clock_timestamp() - INTERVAL '2 seconds',
                    expired_at = clock_timestamp() - INTERVAL '1 second'
                WHERE id IN (?, ?)
                """, Long.valueOf(dueActive), Long.valueOf(duePending));

        // When
        HttpResponse<String> revivedDraft = patch(
                dueDraftRegistration, token, registration("LEFT_IN_PLACE", null, null));
        HttpResponse<String> staleDraftDetail = get(dueDraftDetail, token);
        HttpResponse<String> staleActivePatch = patch(
                dueActive, token, registration("LEFT_IN_PLACE", null, null));
        HttpResponse<String> staleActiveDetail = get(dueActive, token);
        HttpResponse<String> stalePendingPatch = patch(
                duePending, token, registration("HANDED_TO_CENTER", centerId, null));
        HttpResponse<String> stalePendingDetail = get(duePending, token);

        // Then
        assertError(revivedDraft, 404, "COMMON-004");
        assertError(staleDraftDetail, 404, "COMMON-004");
        assertError(staleActivePatch, 404, "COMMON-004");
        assertThat(staleActiveDetail.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(staleActiveDetail.body()).get("status").asString()).isEqualTo("EXPIRED");
        assertError(stalePendingPatch, 404, "COMMON-004");
        assertThat(stalePendingDetail.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(stalePendingDetail.body()).get("status").asString()).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_items WHERE id IN (?, ?)",
                Integer.class, Long.valueOf(dueDraftRegistration), Long.valueOf(dueDraftDetail))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM match_candidates WHERE item_id = ?",
                Integer.class, Long.valueOf(dueDraftRegistration))).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM object_deletion_outbox
                WHERE reason = 'DRAFT_EXPIRED' AND status = 'PENDING'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "SELECT status FROM found_items WHERE id IN (?, ?) ORDER BY id",
                String.class, Long.valueOf(dueActive), Long.valueOf(duePending)))
                .containsExactly("EXPIRED", "EXPIRED");
    }

    @Test
    void dueImageAndHandoverAreRejectedBeforeMutation() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String activeId = createDraft(token);
        String pendingId = createDraft(token);
        String centerId = insertEligibleCenter();
        assertThat(patch(activeId, token, registration("LEFT_IN_PLACE", null, null)).statusCode()).isEqualTo(200);
        assertThat(patch(pendingId, token, registration("HANDED_TO_CENTER", centerId, null)).statusCode())
                .isEqualTo(200);
        long reportId = insertReport(owner.getId(), "OPEN");
        jdbc.update("""
                UPDATE found_items
                SET updated_at = clock_timestamp() - INTERVAL '2 seconds',
                    expired_at = clock_timestamp()
                WHERE id IN (?, ?)
                """, Long.valueOf(activeId), Long.valueOf(pendingId));
        int objectsBefore = storage.keys().size();
        int imagesBefore = jdbc.queryForObject("SELECT count(*) FROM found_item_images", Integer.class);

        // When
        HttpResponse<String> image = imagePut(activeId, token, SECOND_PNG);
        HttpResponse<String> handover = confirm(pendingId, token);

        // Then
        assertError(image, 404, "COMMON-004");
        assertError(handover, 404, "COMMON-004");
        assertThat(storage.keys()).hasSize(objectsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_item_images", Integer.class))
                .isEqualTo(imagesBefore);
        assertThat(jdbc.queryForList("SELECT status FROM found_items WHERE id IN (?, ?) ORDER BY id",
                String.class, Long.valueOf(activeId), Long.valueOf(pendingId)))
                .containsExactly("EXPIRED", "EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_handovers WHERE found_item_id = ?",
                Integer.class, Long.valueOf(pendingId))).isZero();
        assertThat(stale(reportId)).isTrue();
    }

    @Test
    void dueItemsAreRemediatedBeforeDatabaseBackedListPagination() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String dueDraft = createDraft(token);
        String dueActive = createDraft(token);
        String duePending = createDraft(token);
        String futureDraft = createDraft(token);
        String centerId = insertEligibleCenter();
        assertThat(patch(dueActive, token, registration("LEFT_IN_PLACE", null, null)).statusCode()).isEqualTo(200);
        assertThat(patch(duePending, token, registration("HANDED_TO_CENTER", centerId, null)).statusCode())
                .isEqualTo(200);
        jdbc.update("""
                UPDATE found_items SET updated_at = clock_timestamp() - INTERVAL '2 seconds',
                    draft_expires_at = clock_timestamp() WHERE id = ?
                """, Long.valueOf(dueDraft));
        jdbc.update("""
                UPDATE found_items SET updated_at = clock_timestamp() - INTERVAL '2 seconds',
                    expired_at = clock_timestamp() WHERE id IN (?, ?)
                """, Long.valueOf(dueActive), Long.valueOf(duePending));

        // When
        HttpResponse<String> unfiltered = getPath("/api/v1/found-items?page=1&pageSize=20", token);
        HttpResponse<String> drafts = getPath("/api/v1/found-items?page=1&pageSize=20&status=DRAFT", token);
        HttpResponse<String> active = getPath("/api/v1/found-items?page=1&pageSize=20&status=ACTIVE", token);
        HttpResponse<String> pending = getPath(
                "/api/v1/found-items?page=1&pageSize=20&status=PENDING_HANDOVER", token);

        // Then
        assertThat(unfiltered.statusCode()).isEqualTo(200);
        JsonNode unfilteredJson = mapper.readTree(unfiltered.body());
        assertThat(unfilteredJson.get("data").toString()).doesNotContain(dueDraft);
        assertThat(unfilteredJson.get("data").toString()).contains(futureDraft, "EXPIRED");
        assertThat(mapper.readTree(drafts.body()).get("meta").get("totalItems").asLong()).isOne();
        assertThat(mapper.readTree(active.body()).get("meta").get("totalItems").asLong()).isZero();
        assertThat(mapper.readTree(pending.body()).get("meta").get("totalItems").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_items WHERE id = ?",
                Integer.class, Long.valueOf(dueDraft))).isZero();
    }

    @Test
    void dueDraftCurlRegistrationAndDetailReturnConcealedLifecycleResult() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String itemId = createDraft(token);
        Long imageId = jdbc.queryForObject(
                "SELECT id FROM found_item_images WHERE found_item_id = ? AND is_current",
                Long.class, Long.valueOf(itemId));
        jdbc.update("""
                UPDATE found_items SET created_at = fixture.boundary - INTERVAL '24 hours',
                    updated_at = fixture.boundary - INTERVAL '1 second', draft_expires_at = fixture.boundary FROM (SELECT clock_timestamp() AS boundary) fixture WHERE id = ?
                """, Long.valueOf(itemId));

        // When
        CurlResult registration = curl("PATCH", "/api/v1/found-items/" + itemId + "/registration",
                token, registration("LEFT_IN_PLACE", null, null));
        CurlResult detail = curl("GET", "/api/v1/found-items/" + itemId, token, null);

        // Then
        assertThat(registration.status()).isEqualTo(404);
        assertThat(mapper.readTree(registration.body()).get("code").asString()).isEqualTo("COMMON-004");
        assertThat(detail.status()).isEqualTo(404);
        assertThat(mapper.readTree(detail.body()).get("code").asString()).isEqualTo("COMMON-004");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_items WHERE id = ?",
                Integer.class, Long.valueOf(itemId))).isZero();
        assertThat(jdbc.queryForMap("""
                SELECT reason, status FROM object_deletion_outbox WHERE idempotency_key = ?
                """, "found-item-image:" + imageId))
                .containsEntry("reason", "DRAFT_EXPIRED")
                .containsEntry("status", "PENDING");
        System.out.println("R2A_MANUAL_HTTP PATCH=404 GET=404 error=COMMON-004 row=deleted outbox=PENDING");
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

    private HttpResponse<String> get(String id, String token) throws Exception {
        return getPath("/api/v1/found-items/" + id, token);
    }

    private HttpResponse<String> getPath(String path, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> confirm(String id, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        uri("/api/v1/found-items/" + id + ":confirm-handover"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private CurlResult curl(String method, String path, String token, String body) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(java.util.List.of(
                "curl", "-sS", "-i", "--max-time", "15", "-X", method,
                "-H", "Authorization: Bearer " + token));
        if (body != null) {
            command.add("-H");
            command.add("Content-Type: application/json");
            command.add("--data");
            command.add(body);
        }
        command.add("http://127.0.0.1:" + port + path);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String raw;
        try {
            raw = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertThat(process.exitValue()).isZero();
        int bodyStart = raw.lastIndexOf("\r\n\r\n");
        assertThat(bodyStart).isGreaterThan(0);
        int status = Integer.parseInt(raw.substring(0, raw.indexOf("\r\n")).split(" ")[1]);
        return new CurlResult(status, raw.substring(bodyStart + 4));
    }

    private record CurlResult(int status, String body) {
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
