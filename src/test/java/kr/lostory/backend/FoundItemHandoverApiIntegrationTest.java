package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import({
        PostgresTestContainerConfig.class,
        FoundItemDraftApiIntegrationTest.FakeBoundaryConfig.class,
        FoundItemHandoverApiIntegrationTest.FixedClockConfig.class
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundItemHandoverApiIntegrationTest {

    private static final Instant SERVER_TIME = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

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
    }

    @Test
    void nearbyCenterBecomesPendingThenEmptyConfirmationUsesServerClockAndActivatesCandidate() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String itemId = insertDraft(owner.getId());
        String centerId = insertCenter("official_verified", true, 126.9780, 37.5665);

        // When
        HttpResponse<String> pending = patch(itemId, token,
                registration("HANDED_TO_CENTER", centerId, null));
        HttpResponse<String> confirmed = confirm(itemId, token, HttpRequest.BodyPublishers.noBody());

        // Then
        assertThat(pending.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(pending.body()).get("status").asString()).isEqualTo("PENDING_HANDOVER");
        assertThat(confirmed.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(confirmed.body());
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("handoverStatus").asString()).isEqualTo("USER_CONFIRMED");
        assertThat(body.get("centerId").asString()).isEqualTo(centerId);
        assertThat(body.get("handedAt").asString()).isEqualTo(SERVER_TIME.toString());
        assertThat(jdbc.queryForMap("""
                SELECT status, handover_status, center_id, handed_at
                FROM found_items WHERE id = ?
                """, Long.valueOf(itemId)))
                .containsEntry("status", "ACTIVE")
                .containsEntry("handover_status", "USER_CONFIRMED")
                .containsEntry("center_id", Long.valueOf(centerId));
        assertThat(jdbc.queryForObject("SELECT handed_at FROM found_items WHERE id = ?",
                Instant.class, Long.valueOf(itemId))).isEqualTo(SERVER_TIME);
    }

    @Test
    void confirmationRejectsForeignNonPendingAndClientSuppliedBodyWithoutMutation() throws Exception {
        // Given
        User owner = user();
        User foreign = user();
        String ownerToken = tokens.issue(owner).value();
        String centerId = insertCenter("official_verified", true, 126.9780, 37.5665);
        String itemId = pendingItem(owner.getId(), ownerToken, centerId);
        Map<String, Object> pendingState = state(itemId);
        String activeId = confirmedItem(owner.getId(), ownerToken, centerId);
        Map<String, Object> activeState = state(activeId);

        // When
        HttpResponse<String> foreignResponse = confirm(itemId, tokens.issue(foreign).value(),
                HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> clientHandedAt = confirm(itemId, ownerToken, HttpRequest.BodyPublishers.ofString(
                "{\"handedAt\":\"2026-08-23T09:00:00Z\"}"));
        HttpResponse<String> whitespaceBody = confirm(itemId, ownerToken,
                HttpRequest.BodyPublishers.ofString(" "));
        HttpResponse<String> nonPending = confirm(activeId, ownerToken, HttpRequest.BodyPublishers.noBody());

        // Then
        assertError(foreignResponse, 404, "COMMON-004");
        assertError(clientHandedAt, 400, "COMMON-001");
        assertError(whitespaceBody, 400, "COMMON-001");
        assertError(nonPending, 400, "COMMON-001");
        assertThat(state(itemId)).isEqualTo(pendingState);
        assertThat(state(activeId)).isEqualTo(activeState);
    }

    @Test
    void patchReselectsPendingAndConfirmedClaimsAsFreshPendingClaim() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String firstCenter = insertCenter("official_verified", true, 126.9780, 37.5665);
        String secondCenter = insertCenter("official_board_verified", true, 126.9781, 37.5666);
        String itemId = pendingItem(owner.getId(), token, firstCenter);

        // When
        HttpResponse<String> pendingReselection = patch(itemId, token,
                registration("HANDED_TO_CENTER", secondCenter, null));
        HttpResponse<String> confirmed = confirm(itemId, token, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> confirmedReselection = patch(itemId, token,
                registration("HANDED_TO_CENTER", firstCenter, null));

        // Then
        assertClaim(pendingReselection, "PENDING_HANDOVER", "NONE", secondCenter, true);
        assertClaim(confirmed, "ACTIVE", "USER_CONFIRMED", secondCenter, false);
        assertClaim(confirmedReselection, "PENDING_HANDOVER", "NONE", firstCenter, true);
        assertThat(jdbc.queryForObject("SELECT handed_at IS NULL FROM found_items WHERE id = ?",
                Boolean.class, Long.valueOf(itemId))).isTrue();
    }

    @Test
    void switchingToLeftOrMovedWithdrawsConfirmedClaim() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String centerId = insertCenter("official_local_verified", true, 126.9780, 37.5665);
        String leftId = confirmedItem(owner.getId(), token, centerId);
        String movedId = confirmedItem(owner.getId(), token, centerId);

        // When
        HttpResponse<String> left = patch(leftId, token, registration("LEFT_IN_PLACE", null, null));
        HttpResponse<String> moved = patch(movedId, token,
                registration("MOVED_TO_SAFE_PLACE", null, "관리실 보관함"));

        // Then
        assertClaim(left, "ACTIVE", "NONE", null, true);
        assertClaim(moved, "ACTIVE", "NONE", null, true);
        assertThat(jdbc.queryForList("""
                SELECT storage_method || ':' || handover_status
                FROM found_items WHERE id IN (?, ?) ORDER BY id
                """, String.class, Long.valueOf(leftId), Long.valueOf(movedId)))
                .containsExactly("LEFT_IN_PLACE:NONE", "MOVED_TO_SAFE_PLACE:NONE");
    }

    @Test
    void registrationRejectsInactiveDistantP1OnlyAndCurrentlyInvalidCentersWithoutMutation() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String eligible = insertCenter("official_verified", true, 126.9780, 37.5665);
        String inactive = insertCenter("official_verified", false, 126.9780, 37.5665);
        String distant = insertCenter("official_verified", true, 127.1000, 37.7000);
        String p1Only = insertCenter("admin_verified", true, 126.9780, 37.5665);
        String itemId = pendingItem(owner.getId(), token, eligible);
        Map<String, Object> before = state(itemId);

        // When
        HttpResponse<String> inactiveResponse = patch(itemId, token,
                registration("HANDED_TO_CENTER", inactive, null));
        HttpResponse<String> distantResponse = patch(itemId, token,
                registration("HANDED_TO_CENTER", distant, null));
        HttpResponse<String> p1OnlyResponse = patch(itemId, token,
                registration("HANDED_TO_CENTER", p1Only, null));
        jdbc.update("UPDATE lost_centers SET is_active = false WHERE id = ?", Long.valueOf(eligible));
        HttpResponse<String> currentlyInvalid = patch(itemId, token,
                registration("HANDED_TO_CENTER", eligible, null));

        // Then
        assertError(inactiveResponse, 400, "COMMON-001");
        assertError(distantResponse, 400, "COMMON-001");
        assertError(p1OnlyResponse, 400, "COMMON-001");
        assertError(currentlyInvalid, 400, "COMMON-001");
        assertThat(state(itemId)).isEqualTo(before);
    }

    @Test
    void confirmationRejectsCenterDeactivatedAfterPendingWithoutMutation() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String centerId = insertCenter("official_verified", true, 126.9780, 37.5665);
        String itemId = pendingItem(owner.getId(), token, centerId);
        Map<String, Object> before = state(itemId);
        jdbc.update("UPDATE lost_centers SET is_active = false WHERE id = ?", Long.valueOf(centerId));

        // When
        HttpResponse<String> response = confirm(itemId, token, HttpRequest.BodyPublishers.noBody());

        // Then
        assertError(response, 400, "COMMON-001");
        assertThat(state(itemId)).isEqualTo(before);
    }

    private User user() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@task8.example", "hash"));
    }

    private String pendingItem(Long ownerId, String token, String centerId) throws Exception {
        String itemId = insertDraft(ownerId);
        assertClaim(patch(itemId, token, registration("HANDED_TO_CENTER", centerId, null)),
                "PENDING_HANDOVER", "NONE", centerId, true);
        return itemId;
    }

    private String confirmedItem(Long ownerId, String token, String centerId) throws Exception {
        String itemId = pendingItem(ownerId, token, centerId);
        assertThat(confirm(itemId, token, HttpRequest.BodyPublishers.noBody()).statusCode()).isEqualTo(200);
        return itemId;
    }

    private String insertDraft(Long finderId) {
        return jdbc.queryForObject("""
                INSERT INTO found_items
                    (finder_id, status, vision_status, handover_status, analysis_generation,
                     created_at, updated_at, draft_expires_at)
                VALUES (?, 'DRAFT', 'READY', 'NONE', 1, ?, ?, ?)
                RETURNING id
                """, String.class, finderId, Timestamp.from(SERVER_TIME), Timestamp.from(SERVER_TIME),
                Timestamp.from(SERVER_TIME.plusSeconds(3600)));
    }

    private String insertCenter(String verificationStatus, boolean active, double longitude, double latitude) {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, '센터', '서울', ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                        '02-0000-0000', '09-18', ?, ?, true, ?, ?)
                RETURNING id
                """, String.class, "task8-" + UUID.randomUUID(), longitude, latitude,
                verificationStatus, active, Timestamp.from(SERVER_TIME), Timestamp.from(SERVER_TIME));
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
        return request("PATCH", "/api/v1/found-items/" + id + "/registration", token,
                HttpRequest.BodyPublishers.ofString(body), true);
    }

    private HttpResponse<String> confirm(
            String id,
            String token,
            HttpRequest.BodyPublisher body
    ) throws Exception {
        return request("POST", "/api/v1/found-items/" + id + ":confirm-handover", token, body, false);
    }

    private HttpResponse<String> request(
            String method,
            String path,
            String token,
            HttpRequest.BodyPublisher body,
            boolean json
    ) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .method(method, body);
        if (json) request.header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Map<String, Object> state(String itemId) {
        return jdbc.queryForMap("""
                SELECT category, found_at, ST_AsText(found_location::geometry) AS found_location,
                       storage_method, storage_description, center_id, handover_status, handed_at,
                       status, updated_at
                FROM found_items WHERE id = ?
                """, Long.valueOf(itemId));
    }

    private void assertClaim(
            HttpResponse<String> response,
            String status,
            String handoverStatus,
            String centerId,
            boolean handedAtNull
    ) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.get("status").asString()).isEqualTo(status);
        assertThat(body.get("handoverStatus").asString()).isEqualTo(handoverStatus);
        assertThat(body.get("centerId").isNull()).isEqualTo(centerId == null);
        if (centerId != null) assertThat(body.get("centerId").asString()).isEqualTo(centerId);
        assertThat(body.get("handedAt").isNull()).isEqualTo(handedAtNull);
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = mapper.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code").asString()).isEqualTo(code);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock task8Clock() {
            return Clock.fixed(SERVER_TIME, ZoneOffset.UTC);
        }
    }
}
