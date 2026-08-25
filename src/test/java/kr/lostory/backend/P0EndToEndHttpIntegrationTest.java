package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.lostory.backend.founditem.application.FoundItemLifecycleCleanupService;
import kr.lostory.backend.founditem.application.VisionJobWorker;
import kr.lostory.backend.founditem.application.VisionProvider;
import kr.lostory.backend.founditem.application.VisionProviderException;
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
@Import({PostgresTestContainerConfig.class, LostReportApiTestClock.Config.class, P0EndToEndHttpIntegrationTest.BoundaryConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "center.nearby-radius=1001")
class P0EndToEndHttpIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");
    private static final byte[] READY_PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
    private static final byte[] FAILED_PNG = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 2};
    private static final String PASSWORD = "Correct-Horse-42";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired VisionJobWorker vision;
    @Autowired FoundItemLifecycleCleanupService cleanup;
    @Autowired LostReportApiTestClock clock;
    @Autowired FoundItemDraftApiIntegrationTest.FakeObjectStorage storage;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_p0_e2e_matcher ON match_candidates");
        jdbc.execute("DROP FUNCTION IF EXISTS fail_p0_e2e_matcher()");
        jdbc.update("DELETE FROM match_candidates");
        jdbc.update("DELETE FROM report_waypoints");
        jdbc.update("DELETE FROM lost_reports");
        jdbc.update("DELETE FROM found_item_vision_jobs");
        jdbc.update("DELETE FROM object_deletion_outbox");
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM found_items");
        jdbc.update("DELETE FROM lost_centers WHERE source_key LIKE 'p0-e2e:%'");
        storage.reset();
        clock.set(NOW);
    }

    @Test
    void signupToConfirmedHandoverAndStaleCandidateGetUsesOnlyPublicHttpContracts() throws Exception {
        // Given
        String token = signupAndLogin("owner");
        long nearCenter = center("near", 100);
        center("outside-1001", 1001);

        // When: photo-first draft and Vision completion
        HttpResponse<String> drafted = multipart(token, READY_PNG);
        String itemId = body(drafted).get("id").asString();
        assertThat(drafted.statusCode()).isEqualTo(201);
        assertThat(body(drafted).get("status").asString()).isEqualTo("DRAFT");
        assertThat(vision.processNext()).isTrue();
        JsonNode ready = body(get("/api/v1/found-items/" + itemId, token));

        // Then: nearby policy, finalization, and empty-body confirmation stay observable
        assertThat(ready.get("visionStatus").asString()).isEqualTo("READY");
        HttpResponse<String> nearby = get("/api/v1/lost-centers/nearby?latitude=35&longitude=128", token);
        assertThat(nearby.statusCode()).isEqualTo(200);
        assertThat(nearby.body()).contains("p0-e2e:near").doesNotContain("p0-e2e:outside-1001");
        HttpResponse<String> pending = request("PATCH", "/api/v1/found-items/" + itemId + "/registration",
                token, registration(nearCenter));
        assertThat(body(pending).get("status").asString()).isEqualTo("PENDING_HANDOVER");
        HttpResponse<String> confirmed = emptyPost("/api/v1/found-items/" + itemId + ":confirm-handover", token);
        assertThat(confirmed.statusCode()).isEqualTo(200);
        assertThat(body(confirmed).get("handoverStatus").asString()).isEqualTo("USER_CONFIRMED");

        // When: report create/update, then stale candidate GET
        HttpResponse<String> created = request("POST", "/api/v1/lost-reports", token, report());
        assertThat(created.statusCode()).isEqualTo(201);
        String reportId = body(created).get("id").asString();
        HttpResponse<String> updated = request("PATCH", "/api/v1/lost-reports/" + reportId, token,
                "{\"description\":\"updated black wallet\"}");
        assertThat(updated.statusCode()).isEqualTo(200);
        jdbc.update("UPDATE lost_reports SET candidates_stale = true WHERE id = ?", Long.valueOf(reportId));
        JsonNode candidates = body(get("/api/v1/lost-reports/" + reportId + "/candidates", token));

        // Then
        assertThat(candidates.get("candidatesStale").asBoolean()).isFalse();
        assertThat(candidates.get("data")).hasSize(1);
        assertThat(candidates.get("data").get(0).propertyNames())
                .containsExactlyInAnyOrder("candidateId", "rank", "score");
        assertThat(candidates.get("data").get(0).get("candidateId").asString()).isEqualTo(itemId);
        assertPublic(nearby.body(), ready.toString(), confirmed.body(), candidates.toString());
        System.out.println("P0_E2E_HTTP signup=201 login=200 draft=201 vision=READY nearby=200 handover=200 report=201/200 candidates=200");
    }

    @Test
    void foreignJwtVisionFailureExpiredDraftAndMatcherFailureKeepExactPrivacyBoundaries() throws Exception {
        // Given
        String owner = signupAndLogin("failure-owner");
        String foreign = signupAndLogin("foreign");
        String activeId = activeItem(owner);
        HttpResponse<String> report = request("POST", "/api/v1/lost-reports", owner, report());
        String reportId = body(report).get("id").asString();
        assertError(get("/api/v1/found-items/" + activeId, foreign), 404, "COMMON-004");
        assertError(get("/api/v1/lost-reports/" + reportId + "/candidates", foreign), 404, "COMMON-004");

        // When: known Vision failure exhausts three deterministic attempts
        String failedId = body(multipart(owner, FAILED_PNG)).get("id").asString();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(vision.processNext()).isTrue();
            jdbc.update("UPDATE found_item_vision_jobs SET next_attempt_at = clock_timestamp() WHERE found_item_id = ?",
                    Long.valueOf(failedId));
        }
        JsonNode failed = body(get("/api/v1/found-items/" + failedId, owner));
        assertThat(failed.get("visionStatus").asString()).isEqualTo("FAILED");

        // When: forced matcher rollback through real owner GET
        jdbc.update("UPDATE lost_reports SET candidates_stale = true WHERE id = ?", Long.valueOf(reportId));
        installMatcherFailure();
        HttpResponse<String> matcherFailure;
        try {
            matcherFailure = get("/api/v1/lost-reports/" + reportId + "/candidates", owner);
        } finally {
            jdbc.execute("DROP TRIGGER fail_p0_e2e_matcher ON match_candidates");
            jdbc.execute("DROP FUNCTION fail_p0_e2e_matcher()");
        }
        assertError(matcherFailure, 500, "COMMON-005");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id = ?",
                Boolean.class, Long.valueOf(reportId))).isTrue();

        // When: injected clock reaches exact draft TTL and cleanup removes draft
        Instant draftExpiry = Instant.parse(failed.get("draftExpiresAt").asString());
        clock.set(draftExpiry);
        assertThat(cleanup.runCleanup().deletedDrafts()).isOne();
        HttpResponse<String> expiredDraft = get("/api/v1/found-items/" + failedId, owner);

        // Then
        assertError(expiredDraft, 404, "COMMON-004");
        assertPublic(failed.toString(), matcherFailure.body(), expiredDraft.body());
        System.out.println("P0_E2E_BOUNDARIES foreign=404 vision=FAILED matcher=500 expired-draft=404 privacy=public-only");
    }

    private String signupAndLogin(String label) throws Exception {
        String email = label + "-" + UUID.randomUUID() + "@example.test";
        String credentials = "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"P0 사용자\"}"
                .formatted(email, PASSWORD);
        assertThat(request("POST", "/api/v1/auth/signup", null, credentials).statusCode()).isEqualTo(201);
        HttpResponse<String> login = request("POST", "/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD));
        assertThat(login.statusCode()).isEqualTo(200);
        return body(login).get("accessToken").asString();
    }

    private String activeItem(String token) throws Exception {
        String id = body(multipart(token, READY_PNG)).get("id").asString();
        assertThat(vision.processNext()).isTrue();
        assertThat(request("PATCH", "/api/v1/found-items/" + id + "/registration", token,
                registration(null)).statusCode()).isEqualTo(200);
        return id;
    }

    private long center(String name, double meters) {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers (source_key, name, address, location, contact_phone, operating_hours,
                    verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, ?, 'address', ST_Project(ST_SetSRID(ST_MakePoint(128, 35), 4326)::geography,
                    ?, radians(90)), '02-0000-0000', 'always', 'official_verified', true, false, now(), now())
                RETURNING id
                """, Long.class, "p0-e2e:" + name, "p0-e2e:" + name, meters);
    }

    private String registration(Long centerId) {
        String method = centerId == null ? "LEFT_IN_PLACE" : "HANDED_TO_CENTER";
        String center = centerId == null ? "null" : "\"" + centerId + "\"";
        return """
                {"category":"WALLET","foundAt":"2026-08-24T08:00:00Z",
                 "foundLocation":{"latitude":35,"longitude":128},
                 "confirmedFeatures":{"color":"BLACK","publicDescription":"black wallet"},
                 "storageMethod":"%s","centerId":%s,"storageDescription":null}
                """.formatted(method, center);
    }

    private String report() {
        return """
                {"category":"WALLET","description":"black wallet",
                 "lostAtFrom":"2026-08-24T07:00:00Z","lostAtTo":"2026-08-24T09:00:00Z",
                 "waypoints":[{"ordinal":1,"point":{"latitude":35,"longitude":128}}]}
                """;
    }

    private HttpResponse<String> multipart(String token, byte[] bytes) throws Exception {
        String boundary = "p0-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; "
                + "filename=\"item.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(uri("/api/v1/found-items/drafts"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build());
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return send(request.build());
    }

    private HttpResponse<String> emptyPost(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode error = body(response);
        assertThat(error.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(error.get("code").asString()).isEqualTo(code);
    }

    private void assertPublic(String... bodies) {
        assertThat(String.join("\n", bodies)).doesNotContain(
                "finderId", "reporterId", "objectKey", "storageKey", "imageBytes", "confidence", "rawLabel");
    }

    private void installMatcherFailure() {
        jdbc.execute("""
                CREATE FUNCTION fail_p0_e2e_matcher() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced matcher failure'; END; $$
                """);
        jdbc.execute("""
                CREATE TRIGGER fail_p0_e2e_matcher BEFORE DELETE ON match_candidates
                FOR EACH ROW EXECUTE FUNCTION fail_p0_e2e_matcher()
                """);
    }

    @TestConfiguration
    static class BoundaryConfig {
        @Bean @Primary
        FoundItemDraftApiIntegrationTest.FakeObjectStorage objectStorage() {
            return new FoundItemDraftApiIntegrationTest.FakeObjectStorage();
        }

        @Bean @Primary
        VisionProvider visionProvider() {
            return (bytes, request) -> {
                if (bytes[bytes.length - 1] == 2) throw new VisionProviderException(false);
                return new VisionProvider.VisionResult(
                        List.of(new VisionProvider.Label("wallet", 0.95)),
                        List.of(new VisionProvider.Color(5, 5, 5, 1, 0.9)));
            };
        }
    }
}
