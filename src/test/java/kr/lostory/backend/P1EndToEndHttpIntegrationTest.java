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
import java.util.Set;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.founditem.application.VisionJobWorker;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, FoundItemDraftApiIntegrationTest.FakeBoundaryConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "partner.activation-base-url=https://app.example/partner-activation",
                "vision.worker-initial-delay=PT1H",
                "POINT_SIGNUP_GRANT=12",
                "POINT_CANDIDATE_ACCESS_COST=2",
                "POINT_CENTER_CONFIRMED_RETURN_REWARD=7"
        })
// allow: SIZE_OK — one required HTTP narrative and its local request/assertion helpers.
class P1EndToEndHttpIntegrationTest {

    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1
    };
    private static final String PASSWORD = "safe-password-123";
    private static final String PRIVATE_FEATURE = "engraving-private-do-not-expose";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtTokenService tokens;
    @Autowired VisionJobWorker vision;
    @Autowired FoundItemDraftApiIntegrationTest.FakeObjectStorage storage;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_logs");
        jdbc.update("DELETE FROM candidate_access_idempotency_receipts");
        jdbc.update("DELETE FROM candidate_accesses");
        jdbc.update("DELETE FROM point_ledger");
        jdbc.update("DELETE FROM point_accounts");
        jdbc.update("DELETE FROM return_records");
        jdbc.update("DELETE FROM match_candidates");
        jdbc.update("DELETE FROM report_waypoints");
        jdbc.update("DELETE FROM lost_reports");
        jdbc.update("DELETE FROM center_handovers");
        jdbc.update("DELETE FROM center_activation_tokens");
        jdbc.update("DELETE FROM center_partnerships");
        jdbc.update("DELETE FROM found_item_vision_jobs");
        jdbc.update("DELETE FROM object_deletion_outbox");
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM found_items");
        jdbc.update("DELETE FROM vision_daily_admissions");
        jdbc.update("DELETE FROM lost_centers");
        storage.reset();
    }

    @AfterEach
    void cleanupFixture() {
        reset();
        jdbc.update("DELETE FROM users WHERE email LIKE '%@task10.invalid' OR email LIKE '%@task10-admin.invalid'");
    }

    @Test
    void signupPhotoPartnershipHandoverUnlockAndReturnCompleteOnePrivateP1Lifecycle() throws Exception {
        // Given: finder and report owner receive signup points through public HTTP.
        Identity finder = signupAndLogin("finder");
        Identity owner = signupAndLogin("owner");
        assertBalance(finder.token(), 12);
        assertBalance(owner.token(), 12);

        User admin = users.saveAndFlush(new User(
                UUID.randomUUID() + "@task10-admin.invalid", "hash", "Task10 Admin", UserRole.ADMIN));
        String adminToken = tokens.issue(admin).value();
        String centerId = eligibleCenter().toString();

        // When: photo-first draft is analyzed, finalized to center handover, and user-confirmed.
        HttpResponse<String> drafted = multipart(finder.token());
        JsonNode draft = expect(drafted, 201);
        assertThat(draft.get("status").asString()).isEqualTo("DRAFT");
        assertThat(vision.processNext()).isTrue();
        String itemId = draft.get("id").asString();
        JsonNode ready = expect(get("/api/v1/found-items/" + itemId, finder.token()), 200);
        assertThat(ready.get("visionStatus").asString()).isEqualTo("READY");
        HttpResponse<String> finalized = patch("/api/v1/found-items/" + itemId + "/registration",
                finder.token(), registration(centerId));
        assertThat(expect(finalized, 200).get("status").asString()).isEqualTo("PENDING_HANDOVER");
        HttpResponse<String> confirmed = postEmpty(
                "/api/v1/found-items/" + itemId + ":confirm-handover", finder.token());
        assertThat(expect(confirmed, 200).get("handoverStatus").asString()).isEqualTo("USER_CONFIRMED");

        // When: admin creates/approves partnership and public activation creates manager identity.
        String managerEmail = "manager-" + UUID.randomUUID() + "@task10.invalid";
        JsonNode partnership = expect(post("/api/v1/admin/partner-centers", adminToken,
                "{\"centerId\":\"%s\",\"manager\":{\"email\":\"%s\",\"displayName\":\"Manager\"}}"
                        .formatted(centerId, managerEmail)), 201);
        String partnershipId = partnership.get("partnershipId").asString();
        JsonNode approved = expect(post("/api/v1/admin/partner-centers/" + partnershipId + ":approve",
                adminToken, "{}"), 200);
        String activationUrl = approved.get("activationUrl").asString();
        String activationToken = activationUrl.substring(activationUrl.lastIndexOf('/') + 1);
        HttpResponse<String> activated = post("/api/v1/partner-manager-activations/" + activationToken,
                null, "{\"password\":\"%s\"}".formatted(PASSWORD));
        assertThat(expect(activated, 200).get("status").asString()).isEqualTo("ACTIVE");
        String managerToken = login(managerEmail);

        // When: assigned manager lists and accepts handover through dashboard HTTP.
        JsonNode handovers = expect(get("/api/v1/dashboard/handovers?status=USER_CONFIRMED", managerToken), 200);
        assertThat(handovers.get("data")).hasSize(1);
        String handoverId = handovers.get("data").get(0).get("handoverId").asString();
        HttpResponse<String> accepted = post("/api/v1/dashboard/handovers/" + handoverId + ":accept",
                managerToken, "{\"privateFeatures\":[\"%s\"]}".formatted(PRIVATE_FEATURE));
        assertThat(expect(accepted, 200).get("handoverStatus").asString()).isEqualTo("CENTER_CONFIRMED");

        // When: owner reports loss, sees candidate, and unlocks once with identical-key replay.
        JsonNode report = expect(post("/api/v1/lost-reports", owner.token(), report()), 201);
        String reportId = report.get("id").asString();
        HttpResponse<String> candidatesResponse = get(
                "/api/v1/lost-reports/" + reportId + "/candidates", owner.token());
        JsonNode candidates = expect(candidatesResponse, 200);
        assertThat(candidates.get("data")).hasSize(1);
        assertThat(candidates.get("data").get(0).get("candidateId").asString()).isEqualTo(itemId);

        String key = UUID.randomUUID().toString();
        HttpResponse<String> firstUnlock = candidateAccess(reportId, owner.token(), key);
        HttpResponse<String> replayUnlock = candidateAccess(reportId, owner.token(), key);
        JsonNode firstUnlockBody = expect(firstUnlock, 200);
        JsonNode replayUnlockBody = expect(replayUnlock, 200);
        assertThat(firstUnlockBody.get("debitedPoints").asInt()).isEqualTo(2);
        assertThat(firstUnlockBody.get("remainingBalance").asInt()).isEqualTo(10);
        assertThat(firstUnlockBody.get("replayed").asBoolean()).isFalse();
        assertThat(replayUnlockBody.get("debitedPoints").asInt()).isEqualTo(2);
        assertThat(replayUnlockBody.get("remainingBalance").asInt()).isEqualTo(10);
        assertThat(replayUnlockBody.get("replayed").asBoolean()).isTrue();
        assertError(candidateAccess(reportId, owner.token(), "not-a-uuid"), 400, "COMMON-001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_accesses WHERE report_id=?",
                Integer.class, Long.valueOf(reportId))).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE user_id=? "
                        + "AND entry_type='CANDIDATE_ACCESS_DEBIT' AND amount=-2",
                Integer.class, owner.id())).isOne();

        HttpResponse<String> unlockedResponse = get(
                "/api/v1/lost-reports/" + reportId + "/candidates/unlocked", owner.token());
        JsonNode unlocked = expect(unlockedResponse, 200);
        assertThat(unlockedResponse.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(unlocked.get("data")).hasSize(1);
        JsonNode unlockedCandidate = unlocked.get("data").get(0);
        assertThat(unlockedCandidate.propertyNames()).containsExactlyInAnyOrder(
                "candidateId", "rank", "score", "category", "foundDate", "thumbnailUrl",
                "publicFeatures", "center");
        assertThat(unlockedCandidate.get("thumbnailUrl").asString())
                .startsWith("https://signed.example.test/");
        assertThat(unlockedCandidate.toString()).doesNotContain(PRIVATE_FEATURE, "objectKey", "finderId",
                "foundLocation", "scoreBreakdown");

        Instant signedBefore = Instant.now();
        HttpResponse<String> signedImage = get("/api/v1/found-items/" + itemId + "/image", managerToken);
        Instant signedAfter = Instant.now();
        JsonNode signedBody = expect(signedImage, 200);
        assertThat(signedImage.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(signedBody.propertyNames()).containsExactlyInAnyOrder("url", "expiresAt");
        Instant expiresAt = Instant.parse(signedBody.get("expiresAt").asString());
        assertThat(expiresAt).isBetween(signedBefore.plusSeconds(299), signedAfter.plusSeconds(301));

        // When: manager records return twice; replay is identical and reward is durable once.
        String returnRequest = "{\"itemId\":\"%s\",\"reportId\":\"%s\"}".formatted(itemId, reportId);
        HttpResponse<String> returned = post("/api/v1/dashboard/returns", managerToken, returnRequest);
        HttpResponse<String> returnReplay = post("/api/v1/dashboard/returns", managerToken, returnRequest);
        JsonNode returnedBody = expect(returned, 201);
        assertThat(expect(returnReplay, 201)).isEqualTo(returnedBody);
        assertThat(returnedBody.get("status").asString()).isEqualTo("RETURNED");
        assertThat(returnedBody.get("rewardGranted").asInt()).isEqualTo(7);

        // Then: balances/ledgers converge, returned item disappears, and report stays open.
        assertBalance(finder.token(), 19);
        assertBalance(owner.token(), 10);
        JsonNode finderLedger = expect(get("/api/v1/points/ledger?page=1&pageSize=20", finder.token()), 200);
        JsonNode ownerLedger = expect(get("/api/v1/points/ledger?page=1&pageSize=20", owner.token()), 200);
        assertLedger(finderLedger, Set.of("SIGNUP_GRANT:12", "CENTER_RETURN_REWARD:7"));
        assertLedger(ownerLedger, Set.of("SIGNUP_GRANT:12", "CANDIDATE_ACCESS_DEBIT:-2"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_records WHERE found_item_id=? AND lost_report_id=?",
                Integer.class, Long.valueOf(itemId), Long.valueOf(reportId))).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE user_id=? "
                        + "AND entry_type='CENTER_RETURN_REWARD' AND amount=7",
                Integer.class, finder.id())).isOne();

        HttpResponse<String> afterReturnResponse = get(
                "/api/v1/lost-reports/" + reportId + "/candidates/unlocked", owner.token());
        JsonNode afterReturn = expect(afterReturnResponse, 200);
        assertThat(afterReturn.get("data")).isEmpty();
        JsonNode finalReport = expect(get("/api/v1/lost-reports/" + reportId, owner.token()), 200);
        assertThat(finalReport.get("status").asString()).isEqualTo("OPEN");

        String publicBodies = String.join("\n", candidatesResponse.body(), handovers.toString(), accepted.body(),
                unlocked.toString(), returned.body(), returnReplay.body(), afterReturn.toString(), finalReport.toString());
        assertThat(publicBodies).doesNotContain(PRIVATE_FEATURE, managerEmail, activationToken, activationUrl,
                "finderId", "reporterId", "objectKey", "storageKey", "scoreBreakdown", "idempotencyKey");
        System.out.println("R6_POINT_POLICY_HTTP_OBSERVABLE signup=12/12 debit=2 reward=7 "
                + "candidate-response=2/10 return-response=7 ledger=-2/+7 staging-balance-mutation=0 "
                + "draft=201 vision=READY "
                + "partnership=201/200 activation=200 handover=200/200 report=201 candidates=200 "
                + "unlock=200/200 malformed=400 thumbnail=signed/no-store/ttl-300 "
                + "return=201/201 final-balance=19/10 report=OPEN candidates-after-return=0 privacy=public-only");
    }

    private Identity signupAndLogin(String label) throws Exception {
        String email = label + "-" + UUID.randomUUID() + "@task10.invalid";
        JsonNode signup = expect(post("/api/v1/auth/signup", null,
                "{\"email\":\"%s\",\"password\":\"%s\",\"displayName\":\"Task10 User\"}"
                        .formatted(email, PASSWORD)), 201);
        return new Identity(Long.valueOf(signup.get("id").asString()), login(email));
    }

    private String login(String email) throws Exception {
        JsonNode login = expect(post("/api/v1/auth/login", null,
                "{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)), 200);
        return login.get("accessToken").asString();
    }

    private void assertBalance(String token, int expected) throws Exception {
        JsonNode balance = expect(get("/api/v1/points/balance", token), 200);
        assertThat(balance.propertyNames()).containsExactly("balance");
        assertThat(balance.get("balance").asInt()).isEqualTo(expected);
    }

    private void assertLedger(JsonNode body, Set<String> expected) {
        assertThat(body.get("meta").get("totalItems").asInt()).isEqualTo(expected.size());
        assertThat(body.get("data")).allSatisfy(entry -> assertThat(entry.propertyNames())
                .containsExactlyInAnyOrder("id", "type", "amount", "referenceType", "referenceId", "createdAt"));
        assertThat(body.get("data").values().stream()
                .map(entry -> entry.get("type").asString() + ":" + entry.get("amount").asInt())
                .collect(java.util.stream.Collectors.toSet())).isEqualTo(expected);
        assertThat(body.toString()).doesNotContain("userId", "idempotency", "reason");
    }

    private String registration(String centerId) {
        Instant foundAt = Instant.now().minus(Duration.ofHours(1));
        return """
                {"category":"WALLET","foundAt":"%s",
                 "foundLocation":{"latitude":37.5665,"longitude":126.9780},
                 "confirmedFeatures":{"color":"BLACK","publicDescription":"public black wallet"},
                 "storageMethod":"HANDED_TO_CENTER","centerId":"%s","storageDescription":null}
                """.formatted(foundAt, centerId);
    }

    private Long eligibleCenter() {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, 'P1 Lifecycle Center', 'Seoul',
                        ST_SetSRID(ST_MakePoint(126.9780, 37.5665), 4326)::geography,
                        '02-0000-0000', '09-18', 'official_verified', true, false, now(), now())
                RETURNING id
                """, Long.class, "task10:" + UUID.randomUUID());
    }

    private String report() {
        Instant now = Instant.now();
        return """
                {"category":"WALLET","description":"public black wallet",
                 "lostAtFrom":"%s","lostAtTo":"%s",
                 "waypoints":[{"ordinal":1,"point":{"latitude":37.5665,"longitude":126.9780}}]}
                """.formatted(now.minus(Duration.ofHours(2)), now.plus(Duration.ofHours(1)));
    }

    private HttpResponse<String> multipart(String token) throws Exception {
        String boundary = "p1-" + UUID.randomUUID();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; "
                + "filename=\"wallet.png\"\r\nContent-Type: image/png\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        bytes.write(PNG);
        bytes.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(HttpRequest.newBuilder(uri("/api/v1/found-items/drafts"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build());
    }

    private HttpResponse<String> candidateAccess(String reportId, String token, String key) throws Exception {
        return send(HttpRequest.newBuilder(uri("/api/v1/lost-reports/" + reportId + "/candidate-accesses"))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build());
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        return request("POST", path, token, body);
    }

    private HttpResponse<String> patch(String path, String token, String body) throws Exception {
        return request("PATCH", path, token, body);
    }

    private HttpResponse<String> postEmpty(String path, String token) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return send(request.build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        HttpRequest bounded = HttpRequest.newBuilder(request, (name, value) -> true)
                .timeout(REQUEST_TIMEOUT).build();
        return http.send(bounded, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private JsonNode expect(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        JsonNode body = expect(response, status);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code").asString()).isEqualTo(code);
    }

    private record Identity(Long id, String token) {
    }
}
