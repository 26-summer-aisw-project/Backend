package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.config.PartnerProperties;
import kr.lostory.backend.partner.application.PartnerActivationDeliveryCipher;
import kr.lostory.backend.partner.domain.PartnerActivationDelivery;
import kr.lostory.backend.partner.domain.PartnerActivationDeliveryRepository;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "partner.activation-base-url=https://app.example/partner-activation")
class PartnerCenterApiIntegrationTest {

    private static final String PASSWORD = "{\"password\":\"safe-password-123\"}";
    private static final long TIMEOUT = 15;
    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired Validator validator;
    @Autowired PartnerActivationDeliveryRepository activationDeliveries;
    @Autowired PartnerActivationDeliveryCipher deliveryCipher;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final List<ExecutorService> executors = new ArrayList<>();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_logs WHERE target_type = 'CENTER_PARTNERSHIP'");
        jdbc.update("DELETE FROM partner_activation_delivery_outbox");
        jdbc.update("DELETE FROM center_activation_tokens");
        jdbc.update("DELETE FROM center_partnerships");
        jdbc.update("DELETE FROM users WHERE email LIKE 'task5-%@example.test'");
        jdbc.update("DELETE FROM lost_centers WHERE source_key LIKE 'task5:%'");
    }

    @AfterEach
    void stopExecutors() throws InterruptedException {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void createNormalizesPendingManagerIdentity() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long center = center();
        HttpResponse<String> response = create(admin, center, "Manager@Center.Example", "Center Manager");
        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("centerId").asString()).isEqualTo(center.toString());
        assertThat(body.get("status").asString()).isEqualTo("PENDING");
        assertThat(body.get("managerEmail").asString()).isEqualTo("manager@center.example");
    }

    @Test
    void createRejectsExistingEmailAndMissingCenter() throws Exception {
        User admin = user(UserRole.ADMIN);
        User existing = user(UserRole.USER);
        error(create(admin, center(), existing.getEmail().toUpperCase(), "Duplicate"), 409, "AUTH-001");
        error(create(admin, Long.MAX_VALUE, "new@example.test", "Missing"), 404, "COMMON-004");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_partnerships", Integer.class)).isZero();
    }

    @Test
    void approvalReturnsOnlyNonSecretActivationMetadata() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long partnership = partnership(admin, center(), "task5-metadata-only@example.test");

        JsonNode body = json.readTree(approve(admin, partnership).body());

        assertThat(body.propertyNames()).containsExactlyInAnyOrder("partnershipId", "status", "expiresAt");
        assertThat(body.get("status").asString()).isEqualTo("PENDING_ACTIVATION");
        PartnerActivationDelivery delivery = activationDeliveries
                .findByPartnershipIdAndSupersededAtIsNull(partnership).orElseThrow();
        String activationUrl = deliveryCipher.decrypt(delivery);
        assertThat(delivery.getCiphertext()).isNotEqualTo(activationUrl.getBytes(StandardCharsets.UTF_8));
        assertThat(dbText()).doesNotContain(activationUrl);
    }

    @Test
    void approveReissuesHashOnlyTwentyFourHourToken() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long partnership = partnership(admin, center(), "task5-reissue@example.test");
        String oldToken = token(approve(admin, partnership));
        String currentToken = token(approve(admin, partnership));
        assertThat(oldToken).isNotEqualTo(currentToken).hasSize(43);
        error(activate(oldToken), 404, "COMMON-004");
        assertThat(activate(currentToken).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM max(expires_at - issued_at)) "
                + "FROM center_activation_tokens", Long.class)).isEqualTo(86400L);
        assertThat(activationDeliveries.findAllByPartnershipIdOrderById(partnership)).satisfiesExactly(
                oldDelivery -> assertThat(oldDelivery.getSupersededAt()).isNotNull(),
                currentDelivery -> assertThat(currentDelivery.getSupersededAt()).isNull());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_activation_delivery_outbox "
                + "WHERE partnership_id=? AND superseded_at IS NOT NULL", Integer.class, partnership)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_activation_delivery_outbox "
                + "WHERE partnership_id=? AND superseded_at IS NULL", Integer.class, partnership)).isOne();
        assertThat(dbText()).doesNotContain(oldToken, currentToken, "activationUrl");
    }

    @Test
    void concurrentApprovalsLeaveExactlyOneUsableCapability() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long partnership = partnership(admin, center(), "task5-approve-race@example.test");
        CyclicBarrier barrier = new CyclicBarrier(3);
        ExecutorService pool = pool();
        Future<HttpResponse<String>> first = pool.submit(() -> after(barrier, () -> approve(admin, partnership)));
        Future<HttpResponse<String>> second = pool.submit(() -> after(barrier, () -> approve(admin, partnership)));
        barrier.await(5, TimeUnit.SECONDS);
        HttpResponse<String> a = first.get(TIMEOUT, TimeUnit.SECONDS);
        HttpResponse<String> b = second.get(TIMEOUT, TimeUnit.SECONDS);
        assertThat(List.of(a.statusCode(), b.statusCode())).containsOnly(200);
        List<String> capabilities = activationDeliveries.findAllByPartnershipIdOrderById(partnership).stream()
                .map(deliveryCipher::decrypt)
                .map(url -> url.substring(url.lastIndexOf('/') + 1))
                .toList();
        assertThat(capabilities).hasSize(2).doesNotHaveDuplicates();
        List<Integer> activationStatuses = new ArrayList<>();
        for (String capability : capabilities) activationStatuses.add(activate(capability).statusCode());
        assertThat(activationStatuses).containsExactlyInAnyOrder(200, 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_activation_delivery_outbox "
                + "WHERE partnership_id=?", Integer.class, partnership)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_activation_delivery_outbox "
                + "WHERE partnership_id=? AND superseded_at IS NULL", Integer.class, partnership)).isOne();
    }

    @Test
    void outboxPersistenceFailureRollsBackApproval() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long partnership = partnership(admin, center(), "task5-outbox-rollback@example.test");
        jdbc.execute("CREATE FUNCTION fail_partner_delivery_insert() RETURNS trigger LANGUAGE plpgsql "
                + "AS 'BEGIN RAISE EXCEPTION ''forced delivery failure''; END'");
        jdbc.execute("CREATE TRIGGER fail_partner_delivery BEFORE INSERT ON partner_activation_delivery_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION fail_partner_delivery_insert()");
        try {
            assertThat(approve(admin, partnership).statusCode()).isEqualTo(500);
        } finally {
            jdbc.execute("DROP TRIGGER fail_partner_delivery ON partner_activation_delivery_outbox");
            jdbc.execute("DROP FUNCTION fail_partner_delivery_insert()");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM center_partnerships WHERE id=?",
                String.class, partnership)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_activation_tokens WHERE partnership_id=?",
                Integer.class, partnership)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_activation_delivery_outbox "
                + "WHERE partnership_id=?", Integer.class, partnership)).isZero();
    }

    @Test
    void auditFailureRollsBackEncryptedDeliveryAndToken() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long partnership = partnership(admin, center(), "task5-audit-rollback@example.test");
        jdbc.execute("CREATE FUNCTION fail_partner_approval_audit() RETURNS trigger LANGUAGE plpgsql "
                + "AS 'BEGIN IF NEW.action = ''PARTNER_CENTER_APPROVED'' THEN "
                + "RAISE EXCEPTION ''forced audit failure''; END IF; RETURN NEW; END'");
        jdbc.execute("CREATE TRIGGER fail_partner_approval_audit BEFORE INSERT ON audit_logs "
                + "FOR EACH ROW EXECUTE FUNCTION fail_partner_approval_audit()");
        try {
            assertThat(approve(admin, partnership).statusCode()).isEqualTo(500);
        } finally {
            jdbc.execute("DROP TRIGGER fail_partner_approval_audit ON audit_logs");
            jdbc.execute("DROP FUNCTION fail_partner_approval_audit()");
        }
        assertThat(jdbc.queryForObject("SELECT status FROM center_partnerships WHERE id=?",
                String.class, partnership)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_activation_tokens WHERE partnership_id=?",
                Integer.class, partnership)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM partner_activation_delivery_outbox "
                + "WHERE partnership_id=?", Integer.class, partnership)).isZero();
    }

    @Test
    void activationFirstMakesApprovalConflictAndReplayNotFound() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long partnership = partnership(admin, center(), "task5-activation-first@example.test");
        String capability = token(approve(admin, partnership));
        assertThat(activate(capability).statusCode()).isEqualTo(200);
        error(approve(admin, partnership), 409, "STATE-001");
        error(activate(capability), 404, "COMMON-004");
    }

    @Test
    void reissueFirstConcealsOldActivation() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long partnership = partnership(admin, center(), "task5-reissue-first@example.test");
        String old = token(approve(admin, partnership));
        String current = token(approve(admin, partnership));
        error(activate(old), 404, "COMMON-004");
        assertThat(activate(current).statusCode()).isEqualTo(200);
    }

    @Test
    void barriersControlActivationFirstAndReissueFirstRaces() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long activationFirst = partnership(admin, center(), "task5-barrier-activation@example.test");
        String activationFirstToken = token(approve(admin, activationFirst));
        CyclicBarrier firstBarrier = new CyclicBarrier(3);
        CountDownLatch activationDone = new CountDownLatch(1);
        ExecutorService firstPool = pool();
        Future<HttpResponse<String>> activation = firstPool.submit(() -> after(firstBarrier, () -> {
            HttpResponse<String> result = activate(activationFirstToken);
            activationDone.countDown();
            return result;
        }));
        Future<HttpResponse<String>> lateApproval = firstPool.submit(() -> after(firstBarrier, () -> {
            assertThat(activationDone.await(5, TimeUnit.SECONDS)).isTrue();
            return approve(admin, activationFirst);
        }));
        firstBarrier.await(5, TimeUnit.SECONDS);
        assertThat(activation.get(TIMEOUT, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        error(lateApproval.get(TIMEOUT, TimeUnit.SECONDS), 409, "STATE-001");

        Long reissueFirst = partnership(admin, center(), "task5-barrier-reissue@example.test");
        String stale = token(approve(admin, reissueFirst));
        CyclicBarrier secondBarrier = new CyclicBarrier(3);
        CountDownLatch reissueDone = new CountDownLatch(1);
        ExecutorService secondPool = pool();
        Future<HttpResponse<String>> reissue = secondPool.submit(() -> after(secondBarrier, () -> {
            HttpResponse<String> result = approve(admin, reissueFirst);
            reissueDone.countDown();
            return result;
        }));
        Future<HttpResponse<String>> oldActivation = secondPool.submit(() -> after(secondBarrier, () -> {
            assertThat(reissueDone.await(5, TimeUnit.SECONDS)).isTrue();
            return activate(stale);
        }));
        secondBarrier.await(5, TimeUnit.SECONDS);
        assertThat(reissue.get(TIMEOUT, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        error(oldActivation.get(TIMEOUT, TimeUnit.SECONDS), 404, "COMMON-004");
    }

    @Test
    void expiredConsumedMalformedAndUnknownTokensShareNotFound() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long expiredPartnership = partnership(admin, center(), "task5-expired@example.test");
        String expired = token(approve(admin, expiredPartnership));
        jdbc.update("UPDATE center_activation_tokens SET issued_at=now()-interval '25 hours', "
                + "expires_at=now()-interval '1 hour' WHERE partnership_id=?", expiredPartnership);
        Long consumedPartnership = partnership(admin, center(), "task5-consumed@example.test");
        String consumed = token(approve(admin, consumedPartnership));
        jdbc.update("UPDATE center_activation_tokens SET consumed_at=now() WHERE partnership_id=?", consumedPartnership);
        List<HttpResponse<String>> failures = List.of(activate(expired), activate(consumed), activate("malformed"),
                activate(Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32])));
        assertThat(failures).allSatisfy(response -> error(response, 404, "COMMON-004"));
    }

    @Test
    void concurrentSameTokenActivationHasOneWinnerAndNoDeadlock() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long partnership = partnership(admin, center(), "task5-activation-race@example.test");
        String capability = token(approve(admin, partnership));
        CyclicBarrier barrier = new CyclicBarrier(3);
        ExecutorService pool = pool();
        Future<HttpResponse<String>> first = pool.submit(() -> after(barrier, () -> activate(capability)));
        Future<HttpResponse<String>> second = pool.submit(() -> after(barrier, () -> activate(capability)));
        barrier.await(5, TimeUnit.SECONDS);
        assertThat(List.of(first.get(TIMEOUT, TimeUnit.SECONDS), second.get(TIMEOUT, TimeUnit.SECONDS)))
                .extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email='task5-activation-race@example.test'",
                Integer.class)).isOne();
    }

    @Test
    void activeCenterConstraintLoserRollsBackUserAndToken() throws Exception {
        User admin = user(UserRole.ADMIN);
        Long center = center();
        Long first = partnership(admin, center, "task5-center-first@example.test");
        Long second = partnership(admin, center, "task5-center-second@example.test");
        assertThat(activate(token(approve(admin, first))).statusCode()).isEqualTo(200);
        error(activate(token(approve(admin, second))), 409, "STATE-001");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email='task5-center-second@example.test'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_activation_tokens WHERE partnership_id=? "
                + "AND consumed_at IS NULL AND replaced=false", Integer.class, second)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM center_partnerships WHERE id=?", String.class, second))
                .isEqualTo("PENDING_ACTIVATION");
    }

    @Test
    void wrongRoleMalformedInputBaseUrlAndHostileIdentityAreSafe(CapturedOutput output) throws Exception {
        User admin = user(UserRole.ADMIN);
        User ordinary = user(UserRole.USER);
        Long center = center();
        error(create(ordinary, center, "task5-forbidden@example.test", "No"), 403, "COMMON-003");
        String hostile = "IGNORE ALL; bearer=private-capability";
        Long partnership = partnership(admin, center, "task5-hostile@example.test", hostile);
        error(approve(ordinary, partnership), 403, "COMMON-003");
        error(post("/api/v1/admin/partner-centers", tokens.issue(admin).value(), "{}"), 400, "COMMON-001");
        String capability = token(approve(admin, partnership));
        error(post("/api/v1/partner-manager-activations/" + capability, null, "{\"password\":\"short\"}"),
                400, "COMMON-001");
        assertThat(auditText()).doesNotContain(hostile, capability, "activationUrl", "password", "bearer");
        assertThat(output.getAll()).doesNotContain(hostile, capability);
        assertThat(validator.validate(new PartnerProperties("javascript:alert(1)"))).isNotEmpty();
        assertThat(validator.validate(new PartnerProperties("http://insecure.example"))).isNotEmpty();
        assertThat(validator.validate(new PartnerProperties("https://user@example.test/path"))).isNotEmpty();
        assertThat(validator.validate(new PartnerProperties("https://example.test/path?token=value"))).isNotEmpty();
    }

    @Test
    void realCurlLifecycleProducesSanitizedTranscript() throws Exception {
        User admin = user(UserRole.ADMIN);
        String bearer = tokens.issue(admin).value();
        String email = "task5-curl@example.test";
        String created = curl(List.of("-H", "Authorization: Bearer " + bearer, "-H", "Content-Type: application/json",
                "-d", body(center(), email, "Curl Manager"), url("/api/v1/admin/partner-centers")));
        Long partnership = Long.valueOf(curlBody(created).get("partnershipId").asString());
        String approved = curl(List.of("-H", "Authorization: Bearer " + bearer, "-H", "Content-Type: application/json",
                "-d", "{}", url("/api/v1/admin/partner-centers/" + partnership + ":approve")));
        String capability = token(curlBody(approved));
        String activated = curl(List.of("-X", "POST", "-H", "Content-Type: application/json", "-d", PASSWORD,
                url("/api/v1/partner-manager-activations/" + capability)));
        String replay = curl(List.of("-X", "POST", "-H", "Content-Type: application/json", "-d", PASSWORD,
                url("/api/v1/partner-manager-activations/" + capability)));
        String sanitized = ("COMMAND create Authorization: Bearer <REDACTED_BEARER>\n"
                + "COMMAND approve Authorization: Bearer <REDACTED_BEARER>\n"
                + "COMMAND activate /<REDACTED_ACTIVATION_TOKEN>\nCREATE\n" + created
                + "\nAPPROVE\n" + approved + "\nACTIVATE\n" + activated
                + "\nREPLAY\n" + replay).replace(bearer, "<REDACTED_BEARER>")
                .replace(capability, "<REDACTED_ACTIVATION_TOKEN>").replace(email, "<REDACTED_MANAGER_EMAIL>")
                .replaceAll("(\\\"(?:partnershipId|centerId|managerUserId)\\\":\\\")\\d+(\\\")",
                        "$1<REDACTED_RESOURCE_ID>$2");
        Path directory = Path.of(".omo/start-work/evidence/api-spec-missing-endpoints/task-5");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("partner-activation-curl-sanitized.txt"), sanitized, StandardCharsets.UTF_8);
        assertThat(created).contains("HTTP/1.1 201");
        assertThat(approved).contains("HTTP/1.1 200");
        assertThat(activated).contains("HTTP/1.1 200");
        assertThat(replay).contains("HTTP/1.1 404");
        assertThat(sanitized).doesNotContain(bearer, capability, email)
                .doesNotMatch("(?s).*(?:partnershipId|centerId|managerUserId)\\\":\\\"\\d+.*")
                .contains("<REDACTED_BEARER>", "<REDACTED_ACTIVATION_TOKEN>",
                        "<REDACTED_RESOURCE_ID>");
        System.out.println("MANUAL_PARTNER_ACTIVATION_OUTBOX_OBSERVABLE "
                + "status=200 fields=partnershipId,status,expiresAt encrypted=true activation=200 replay=404");
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User("task5-" + UUID.randomUUID() + "@example.test", "hash", role.name(), role));
    }

    private Long center() {
        return jdbc.queryForObject("INSERT INTO lost_centers (source_key,name,address,location,contact_phone,"
                + "operating_hours,verification_status,is_active,is_csv_managed,created_at,updated_at) VALUES "
                + "(?,'partner center','address',ST_SetSRID(ST_MakePoint(127,37),4326)::geography,"
                + "'02-0000-0000','always','admin_verified',true,false,now(),now()) RETURNING id",
                Long.class, "task5:" + UUID.randomUUID());
    }

    private Long partnership(User admin, Long center, String email) throws Exception {
        return partnership(admin, center, email, "Center Manager");
    }

    private Long partnership(User admin, Long center, String email, String name) throws Exception {
        HttpResponse<String> response = create(admin, center, email, name);
        assertThat(response.statusCode()).isEqualTo(201);
        return Long.valueOf(json.readTree(response.body()).get("partnershipId").asString());
    }

    private HttpResponse<String> create(User actor, Long center, String email, String name) throws Exception {
        return post("/api/v1/admin/partner-centers", tokens.issue(actor).value(), body(center, email, name));
    }

    private String body(Long center, String email, String name) {
        return "{\"centerId\":\"%d\",\"manager\":{\"email\":\"%s\",\"displayName\":\"%s\"}}"
                .formatted(center, email, name);
    }

    private HttpResponse<String> approve(User actor, Long partnership) throws Exception {
        return post("/api/v1/admin/partner-centers/" + partnership + ":approve", tokens.issue(actor).value(), "{}");
    }

    private HttpResponse<String> activate(String capability) throws Exception {
        return post("/api/v1/partner-manager-activations/" + capability, null, PASSWORD);
    }

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url(path))).timeout(Duration.ofSeconds(TIMEOUT))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return token(json.readTree(response.body()));
    }

    private String token(JsonNode approval) {
        Long partnershipId = Long.valueOf(approval.get("partnershipId").asString());
        String url = deliveryCipher.decrypt(activationDeliveries
                .findByPartnershipIdAndSupersededAtIsNull(partnershipId).orElseThrow());
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code").asString()).isEqualTo(code);
    }

    private String auditText() {
        return jdbc.queryForList("SELECT action,target_type,target_id,metadata_json::text FROM audit_logs "
                + "WHERE target_type='CENTER_PARTNERSHIP'").toString();
    }

    private String dbText() {
        return jdbc.queryForList("SELECT encode(token_hash,'hex'),expires_at::text,consumed_at::text,replaced::text "
                + "FROM center_activation_tokens").toString()
                + jdbc.queryForList("SELECT key_version,expires_at::text,created_at::text,superseded_at::text "
                + "FROM partner_activation_delivery_outbox").toString() + auditText();
    }

    private ExecutorService pool() {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        executors.add(pool);
        return pool;
    }

    private <T> T after(CyclicBarrier barrier, CheckedSupplier<T> supplier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return supplier.get();
    }

    private String curl(List<String> arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("curl", "-sS", "-i", "--max-time", "15"));
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(TIMEOUT, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        return output;
    }

    private JsonNode curlBody(String response) {
        return json.readTree(response.substring(response.indexOf("\r\n\r\n") + 4));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> { T get() throws Exception; }
}
