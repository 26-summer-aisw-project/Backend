package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
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
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardHandoverApiIntegrationTest {

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_logs WHERE target_type = 'CENTER_HANDOVER'");
        jdbc.update("DELETE FROM center_handovers");
        jdbc.update("DELETE FROM center_activation_tokens");
        jdbc.update("DELETE FROM center_partnerships");
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM found_items");
        jdbc.update("DELETE FROM lost_centers");
    }

    @Test
    void assignedManagerListsAndAcceptsOnlyCurrentCenterHandover() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Long centerId = center();
        activate(manager, centerId);
        Long itemId = confirmedItem(centerId);
        Long handoverId = currentHandover(itemId);

        // When
        HttpResponse<String> listed = request("GET", "/api/v1/dashboard/handovers",
                manager, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> accepted = request("POST", "/api/v1/dashboard/handovers/" + handoverId + ":accept",
                manager, HttpRequest.BodyPublishers.ofString("{\"privateFeatures\":[\"redacted-fixture\"]}"));

        // Then
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(listed.body()).get("data").get(0).get("handoverId").asLong())
                .isEqualTo(handoverId);
        assertThat(accepted.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(accepted.body());
        assertThat(body.get("handoverStatus").asString()).isEqualTo("CENTER_CONFIRMED");
        assertThat(body.toString()).doesNotContain("privateFeatures", "redacted-fixture");
        assertThat(jdbc.queryForObject("SELECT status FROM center_handovers WHERE id = ?",
                String.class, handoverId)).isEqualTo("CENTER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT handover_status FROM found_items WHERE id = ?",
                String.class, itemId)).isEqualTo("CENTER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT metadata_json::text FROM audit_logs "
                        + "WHERE target_type='CENTER_HANDOVER' AND target_id=?",
                String.class, handoverId)).doesNotContain("redacted-fixture", "privateFeatures");
    }

    @Test
    void rejectPreservesUserClaimAndSecondDecisionConflicts() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Long centerId = center();
        activate(manager, centerId);
        Long itemId = confirmedItem(centerId);
        Long handoverId = currentHandover(itemId);

        // When
        HttpResponse<String> rejected = request("POST", "/api/v1/dashboard/handovers/" + handoverId + ":reject",
                manager, HttpRequest.BodyPublishers.ofString("{\"reason\":\"not found\"}"));
        HttpResponse<String> repeated = request("POST", "/api/v1/dashboard/handovers/" + handoverId + ":accept",
                manager, HttpRequest.BodyPublishers.ofString("{\"privateFeatures\":[\"x\"]}"));

        // Then
        assertThat(rejected.statusCode()).isEqualTo(200);
        assertThat(json.readTree(rejected.body()).get("handoverStatus").asString()).isEqualTo("REJECTED");
        assertError(repeated, 409, "STATE-001");
        assertThat(jdbc.queryForObject("SELECT handover_status FROM found_items WHERE id = ?",
                String.class, itemId)).isEqualTo("USER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM center_handovers WHERE id = ?",
                String.class, handoverId)).isEqualTo("not found");
    }

    @Test
    void malformedDecisionAndForeignCenterAreRejectedAtBoundary() throws Exception {
        // Given
        User assigned = user(UserRole.CENTER_MANAGER);
        User foreign = user(UserRole.CENTER_MANAGER);
        Long centerId = center();
        activate(assigned, centerId);
        activate(foreign, center());
        Long handoverId = currentHandover(confirmedItem(centerId));

        // When / Then
        assertError(request("POST", "/api/v1/dashboard/handovers/" + handoverId + ":accept",
                assigned, HttpRequest.BodyPublishers.ofString("{\"privateFeatures\":[]}")), 400, "COMMON-001");
        assertError(request("POST", "/api/v1/dashboard/handovers/" + handoverId + ":accept",
                assigned, HttpRequest.BodyPublishers.ofString("{\"privateFeatures\":[\"   \"]}")), 400, "COMMON-001");
        assertError(request("POST", "/api/v1/dashboard/handovers/" + handoverId + ":reject",
                foreign, HttpRequest.BodyPublishers.ofString("{\"reason\":\"foreign\"}")), 403, "COMMON-003");
    }

    @Test
    void realCurlExercisesDecisionMutationReentryMalformedAndCrossCenterMatrix() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        User foreign = user(UserRole.CENTER_MANAGER);
        Long centerId = center();
        activate(manager, centerId);
        activate(foreign, center());
        Long acceptedItem = confirmedItem(centerId);
        Long rejectedItem = confirmedItem(centerId);
        Long malformedItem = confirmedItem(centerId);
        Long acceptedId = currentHandover(acceptedItem);
        Long rejectedId = currentHandover(rejectedItem);
        Long malformedId = currentHandover(malformedItem);
        User acceptedOwner = ownerOf(acceptedItem);
        User rejectedOwner = ownerOf(rejectedItem);
        String changedRegistration = registration(centerId, "curl changed description");

        // When
        CurlResult listed = curl("GET", "/api/v1/dashboard/handovers", manager, null);
        CurlResult accepted = curl("POST", "/api/v1/dashboard/handovers/" + acceptedId + ":accept",
                manager, "{\"privateFeatures\":[\"<REDACTED_PRIVATE_FEATURE>\"]}");
        CurlResult acceptedPatch = curl("PATCH", "/api/v1/found-items/" + acceptedItem + "/registration",
                acceptedOwner, changedRegistration);
        CurlResult rejected = curl("POST", "/api/v1/dashboard/handovers/" + rejectedId + ":reject",
                manager, "{\"reason\":\"not found\"}");
        CurlResult postRejectPatch = curl("PATCH", "/api/v1/found-items/" + rejectedItem + "/registration",
                rejectedOwner, changedRegistration);
        CurlResult postRejectConfirmation = curl("POST",
                "/api/v1/found-items/" + rejectedItem + ":confirm-handover", rejectedOwner, null);
        CurlResult malformedEmpty = curl("POST", "/api/v1/dashboard/handovers/" + malformedId + ":accept",
                manager, "{\"privateFeatures\":[]}");
        CurlResult malformedBlank = curl("POST", "/api/v1/dashboard/handovers/" + malformedId + ":accept",
                manager, "{\"privateFeatures\":[\"   \"]}");
        CurlResult foreignDenied = curl("POST", "/api/v1/dashboard/handovers/" + rejectedId + ":accept",
                foreign, "{\"privateFeatures\":[\"<REDACTED_PRIVATE_FEATURE>\"]}");

        // Then
        assertThat(listed.status()).isEqualTo(200);
        assertThat(accepted.status()).isEqualTo(200);
        assertCurlError(acceptedPatch, 409, "STATE-001");
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(postRejectPatch.status()).isEqualTo(200);
        assertThat(postRejectConfirmation.status()).isEqualTo(200);
        assertCurlError(malformedEmpty, 400, "COMMON-001");
        assertCurlError(malformedBlank, 400, "COMMON-001");
        assertThat(foreignDenied.status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT superseded_at IS NOT NULL FROM center_handovers WHERE id=?",
                Boolean.class, rejectedId)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM center_handovers
                WHERE found_item_id=? AND superseded_at IS NULL AND status='USER_CONFIRMED'
                """, Integer.class, rejectedItem)).isOne();
        assertThat(listed.body() + accepted.body() + acceptedPatch.body() + rejected.body()
                        + postRejectPatch.body() + postRejectConfirmation.body()
                        + malformedEmpty.body() + malformedBlank.body() + foreignDenied.body())
                .doesNotContain("privateFeatures", "<REDACTED_PRIVATE_FEATURE>");
    }

    private User ownerOf(Long itemId) {
        Long ownerId = jdbc.queryForObject("SELECT finder_id FROM found_items WHERE id=?", Long.class, itemId);
        return users.findById(ownerId).orElseThrow();
    }

    private String registration(Long centerId, String publicDescription) {
        return """
                {"category":"WALLET","foundAt":"2026-08-23T08:00:00Z",
                 "foundLocation":{"latitude":37.5665,"longitude":126.9780},
                 "confirmedFeatures":{"color":"BLACK","publicDescription":"%s"},
                 "storageMethod":"HANDED_TO_CENTER","centerId":"%s","storageDescription":null}
                """.formatted(publicDescription, centerId);
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@task7.example", "hash", "Manager", role));
    }

    private Long center() {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, 'center', 'Seoul', ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                        '02-0000-0000', '09-18', 'official_verified', true, true, now(), now())
                RETURNING id
                """, Long.class, "task7:" + UUID.randomUUID());
    }

    private void activate(User manager, Long centerId) {
        jdbc.update("""
                INSERT INTO center_partnerships
                    (center_id, manager_email, manager_display_name, status, manager_user_id,
                     created_at, updated_at, activated_at)
                VALUES (?, ?, 'Manager', 'ACTIVE', ?, now(), now(), now())
                """, centerId, manager.getEmail(), manager.getId());
    }

    private Long confirmedItem(Long centerId) {
        Instant now = Instant.now();
        Long itemId = jdbc.queryForObject("""
                INSERT INTO found_items
                    (finder_id, name, category, description, found_at, found_location,
                     storage_method, center_id, handover_status, handed_at, status, vision_status,
                     analysis_generation, created_at, updated_at, expired_at)
                VALUES (?, 'wallet', 'WALLET', 'black wallet', ?,
                        ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                        'HANDED_TO_CENTER', ?, 'USER_CONFIRMED', ?, 'ACTIVE', 'READY', 1, ?, ?, ?)
                RETURNING id
                """, Long.class, user(UserRole.USER).getId(), Timestamp.from(now.minusSeconds(60)), centerId,
                Timestamp.from(now), Timestamp.from(now.minusSeconds(120)), Timestamp.from(now),
                Timestamp.from(now.plusSeconds(86400)));
        jdbc.update("""
                INSERT INTO center_handovers
                    (found_item_id, center_id, status, user_confirmed_at, created_at)
                VALUES (?, ?, 'USER_CONFIRMED', ?, ?)
                """, itemId, centerId, Timestamp.from(now), Timestamp.from(now));
        return itemId;
    }

    private Long currentHandover(Long itemId) {
        return jdbc.queryForObject("SELECT id FROM center_handovers WHERE found_item_id=? AND superseded_at IS NULL",
                Long.class, itemId);
    }

    private HttpResponse<String> request(
            String method,
            String path,
            User caller,
            HttpRequest.BodyPublisher body
    ) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + tokens.issue(caller).value())
                .header("Content-Type", "application/json")
                .method(method, body)
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }

    private void assertCurlError(CurlResult response, int status, String code) throws Exception {
        assertThat(response.status()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }

    private CurlResult curl(String method, String path, User caller, String body) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(java.util.List.of(
                "curl", "-sS", "-i", "--max-time", "15",
                "-H", "Authorization: Bearer " + tokens.issue(caller).value(),
                "-H", "Content-Type: application/json",
                "-X", method));
        if (body != null) {
            command.add("--data");
            command.add(body);
        }
        command.add("http://127.0.0.1:" + port + path);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String raw;
        try {
            raw = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
        }
        assertThat(process.exitValue()).isZero();
        int bodyStart = raw.lastIndexOf("\r\n\r\n");
        assertThat(bodyStart).isGreaterThan(0);
        String statusLine = raw.substring(0, raw.indexOf("\r\n"));
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        String responseBody = raw.substring(bodyStart + 4);
        System.out.println("DASHBOARD_CURL_RAW_SANITIZED " + method + " " + path
                + "\n" + statusLine + "\n" + responseBody);
        return new CurlResult(status, responseBody);
    }

    private record CurlResult(int status, String body) {
    }
}
