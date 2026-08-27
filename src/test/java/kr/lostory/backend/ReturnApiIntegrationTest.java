package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.application.FoundItemImagePersistenceService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, ReturnApiIntegrationTest.ContentionGateConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReturnApiIntegrationTest {

    private static final AtomicReference<ContentionGate> ACTIVE_ITEM_LOCK_GATE = new AtomicReference<>();
    private static final AtomicReference<ContentionGate> ACTIVE_STALE_GATE = new AtomicReference<>();

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired FoundItemImagePersistenceService imagePersistence;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        ACTIVE_ITEM_LOCK_GATE.set(null);
        ACTIVE_STALE_GATE.set(null);
        jdbc.update("DELETE FROM audit_logs WHERE target_type = 'RETURN_RECORD'");
        jdbc.update("DELETE FROM point_ledger WHERE entry_type = 'CENTER_RETURN_REWARD'");
        jdbc.update("DELETE FROM return_records");
        jdbc.update("DELETE FROM candidate_access_idempotency_receipts");
        jdbc.update("DELETE FROM candidate_accesses");
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
        jdbc.update("DELETE FROM lost_centers");
    }

    @Test
    void postReturnRouteReachesDomainHandler() throws Exception {
        // Given
        User manager = users.saveAndFlush(new User(
                UUID.randomUUID() + "@task8.example", "hash", "Manager", UserRole.CENTER_MANAGER));
        Long centerId = jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, 'center', 'Seoul', ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                        '02-0000-0000', '09-18', 'official_verified', true, true, now(), now())
                RETURNING id
                """, Long.class, "task8-red:" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO center_partnerships
                    (center_id, manager_email, manager_display_name, status, manager_user_id,
                     created_at, updated_at, activated_at)
                VALUES (?, ?, 'Manager', 'ACTIVE', ?, now(), now(), now())
                """, centerId, manager.getEmail(), manager.getId());

        // When
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/dashboard/returns"))
                .header("Authorization", "Bearer " + tokens.issue(manager).value())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"itemId\":\"1\",\"reportId\":\"1\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());

        // Then
        assertThat(response.statusCode()).isEqualTo(409);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("STATE-001");
    }

    @Test
    void acceptedReturnCreatesCanonicalRecordRewardAndAuditExactlyOnce() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture fixture = fixture(manager);

        // When
        HttpResponse<String> created = post(manager, fixture.itemId(), fixture.reportId());
        HttpResponse<String> replay = post(manager, fixture.itemId(), fixture.reportId());

        // Then
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(replay.statusCode()).isEqualTo(201);
        JsonNode body = json.readTree(created.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder(
                "returnId", "itemId", "reportId", "status", "rewardGranted");
        assertThat(body.get("itemId").asString()).isEqualTo(fixture.itemId().toString());
        assertThat(body.get("reportId").asString()).isEqualTo(fixture.reportId().toString());
        assertThat(body.get("status").asString()).isEqualTo("RETURNED");
        assertThat(body.get("rewardGranted").asInt()).isEqualTo(5);
        assertThat(json.readTree(replay.body())).isEqualTo(body);
        assertThat(count("return_records")).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?", String.class,
                fixture.itemId())).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("SELECT status FROM lost_reports WHERE id=?", String.class,
                fixture.reportId())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id=?", Boolean.class,
                fixture.reportId())).isTrue();
        assertThat(rewardCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT balance FROM point_accounts WHERE user_id=?", Integer.class,
                fixture.finder().getId())).isEqualTo(5);
        assertThat(auditCount()).isOne();
        assertThat(created.body()).doesNotContain("finder", "email", "location", "private", "token", "metadata");
    }

    @Test
    void lateReportFailuresRollbackItemStaleAccountRewardAndAudit() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture closed = fixture(manager);
        jdbc.update("UPDATE lost_reports SET status='CLOSED' WHERE id=?", closed.reportId());

        // When
        HttpResponse<String> response = post(manager, closed.itemId(), closed.reportId());

        // Then
        assertError(response, 409, "REPORT_NOT_OPEN");
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?", String.class,
                closed.itemId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id=?", Boolean.class,
                closed.reportId())).isFalse();
        assertThat(count("return_records")).isZero();
        assertThat(rewardCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_accounts WHERE user_id=?", Integer.class,
                closed.finder().getId())).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test
    void authorizationMalformedMismatchAndForeignCenterAreSafe() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        User foreign = user(UserRole.CENTER_MANAGER);
        User regular = user(UserRole.USER);
        Fixture fixture = fixture(manager);
        activate(foreign, center());

        // When / Then
        assertError(post(foreign, fixture.itemId(), fixture.reportId()), 403, "COMMON-003");
        assertError(post(regular, fixture.itemId(), fixture.reportId()), 403, "COMMON-003");
        assertError(postRaw(manager, "{\"itemId\":\"bad\",\"reportId\":\"1\"}"), 400, "COMMON-001");
        assertError(postRaw(manager, "{\"itemId\":\"1\"}"), 400, "COMMON-001");
        assertError(postRaw(manager, "{"), 400, "COMMON-001");
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?", String.class,
                fixture.itemId())).isEqualTo("ACTIVE");
        assertThat(count("return_records")).isZero();
        assertThat(rewardCount()).isZero();
    }

    @Test
    void blockedAndDeletedFindersReceiveRewardWithoutAuthenticationRestoration() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture blocked = fixture(manager);
        Fixture deleted = fixture(manager);
        jdbc.update("UPDATE users SET status='BLOCKED' WHERE id=?", blocked.finder().getId());
        jdbc.update("UPDATE users SET status='DELETED' WHERE id=?", deleted.finder().getId());

        // When
        HttpResponse<String> blockedResult = post(manager, blocked.itemId(), blocked.reportId());
        HttpResponse<String> deletedResult = post(manager, deleted.itemId(), deleted.reportId());

        // Then
        assertThat(blockedResult.statusCode()).isEqualTo(201);
        assertThat(deletedResult.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT balance FROM point_accounts WHERE user_id=?", Integer.class,
                blocked.finder().getId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT balance FROM point_accounts WHERE user_id=?", Integer.class,
                deleted.finder().getId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class,
                blocked.finder().getId())).isEqualTo("BLOCKED");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class,
                deleted.finder().getId())).isEqualTo("DELETED");
    }

    @Test
    void controlledSameReturnRaceHasCanonicalResponsesAndOneDurableReward() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture fixture = fixture(manager);
        ContentionGate lockGate = new ContentionGate(2);
        ACTIVE_ITEM_LOCK_GATE.set(lockGate);
        Callable<HttpResponse<String>> task = () -> post(manager, fixture.itemId(), fixture.reportId());

        // When
        List<HttpResponse<String>> responses;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = executor.submit(task);
            Future<HttpResponse<String>> second = executor.submit(task);
            lockGate.assertBothTransactionsReachedAndRelease();
            responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }

        // Then
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(json.readTree(responses.get(0).body())).isEqualTo(json.readTree(responses.get(1).body()));
        assertThat(count("return_records")).isOne();
        assertThat(rewardCount()).isOne();
        assertThat(auditCount()).isOne();
        assertDurableReturn(manager, fixture);
    }

    @Test
    void controlledDistinctReturnRaceCompletesWithoutDeadlockAndStalesEveryOpenReport() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture firstFixture = fixture(manager);
        Fixture secondFixture = fixture(manager);
        ContentionGate staleGate = new ContentionGate(2);
        ACTIVE_STALE_GATE.set(staleGate);

        // When
        List<HttpResponse<String>> responses;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> first = executor.submit(
                    () -> post(manager, firstFixture.itemId(), firstFixture.reportId()));
            Future<HttpResponse<String>> second = executor.submit(
                    () -> post(manager, secondFixture.itemId(), secondFixture.reportId()));
            staleGate.assertBothTransactionsReachedAndRelease();
            responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }

        // Then
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(count("return_records")).isEqualTo(2);
        assertThat(rewardCount()).isEqualTo(2);
        assertThat(auditCount()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM lost_reports WHERE candidates_stale=false",
                Integer.class)).isZero();
        assertDurableReturn(manager, firstFixture);
        assertDurableReturn(manager, secondFixture);
    }

    @Test
    void nonidenticalHandoverOnlyCollisionReturnsConflictAndRollsBackEveryMutation() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture requested = fixture(manager);
        Fixture storedTuple = fixture(manager);
        Long centerId = jdbc.queryForObject("SELECT center_id FROM center_handovers WHERE id=?",
                Long.class, requested.handoverId());
        jdbc.update("""
                INSERT INTO return_records
                    (handover_id, found_item_id, lost_report_id, finder_id, center_id,
                     recorded_by, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RETURNED', now())
                """, requested.handoverId(), storedTuple.itemId(), storedTuple.reportId(),
                storedTuple.finder().getId(), centerId, manager.getId());

        // When
        HttpResponse<String> response = post(manager, requested.itemId(), requested.reportId());

        // Then
        assertError(response, 409, "STATE-001");
        assertThat(count("return_records")).isOne();
        assertThat(rewardCount()).isZero();
        assertThat(auditCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?", String.class,
                requested.itemId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM lost_reports WHERE id=?", String.class,
                requested.reportId())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id=?", Boolean.class,
                requested.reportId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_accounts WHERE user_id=?", Integer.class,
                requested.finder().getId())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM return_records
                WHERE handover_id=? AND found_item_id=? AND lost_report_id=?
                """, Integer.class, requested.handoverId(), storedTuple.itemId(),
                storedTuple.reportId())).isOne();
    }

    @Test
    void conflictingUniqueResourcesAndInvalidHandoverReportStatesRollback() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture canonical = fixture(manager);
        assertThat(post(manager, canonical.itemId(), canonical.reportId()).statusCode()).isEqualTo(201);
        Long alternateReport = report(user(UserRole.USER).getId());
        jdbc.update("""
                INSERT INTO match_candidates (report_id, item_id, rank, score, score_breakdown, created_at)
                VALUES (?, ?, 1, 98.00, '{}', now())
                """, alternateReport, canonical.itemId());
        Fixture reportCollision = fixture(manager);
        jdbc.update("DELETE FROM match_candidates WHERE report_id=?", reportCollision.reportId());
        jdbc.update("""
                INSERT INTO match_candidates (report_id, item_id, rank, score, score_breakdown, created_at)
                VALUES (?, ?, 2, 97.00, '{}', now())
                """, canonical.reportId(), reportCollision.itemId());
        Fixture unaccepted = fixture(manager);
        jdbc.update("""
                UPDATE center_handovers SET status='USER_CONFIRMED', decided_at=NULL, decided_by=NULL
                WHERE id=?
                """, unaccepted.handoverId());
        jdbc.update("UPDATE found_items SET handover_status='USER_CONFIRMED' WHERE id=?", unaccepted.itemId());
        Fixture rejected = fixture(manager);
        jdbc.update("""
                UPDATE center_handovers
                SET status='REJECTED', rejection_reason='not present'
                WHERE id=?
                """, rejected.handoverId());
        jdbc.update("UPDATE found_items SET handover_status='USER_CONFIRMED' WHERE id=?", rejected.itemId());
        Fixture superseded = fixture(manager);
        jdbc.update("UPDATE center_handovers SET superseded_at=now() WHERE id=?", superseded.handoverId());
        Fixture expired = fixture(manager);
        jdbc.update("""
                UPDATE lost_reports
                SET created_at=now()-interval '2 days', expired_at=now()-interval '1 day'
                WHERE id=?
                """, expired.reportId());

        // When / Then
        assertError(post(manager, canonical.itemId(), alternateReport), 409, "STATE-001");
        assertError(post(manager, reportCollision.itemId(), canonical.reportId()), 409, "STATE-001");
        assertError(post(manager, unaccepted.itemId(), unaccepted.reportId()), 409, "STATE-001");
        assertError(post(manager, rejected.itemId(), rejected.reportId()), 409, "STATE-001");
        assertError(post(manager, superseded.itemId(), superseded.reportId()), 409, "STATE-001");
        assertError(post(manager, expired.itemId(), expired.reportId()), 409, "REPORT_NOT_OPEN");
        assertThat(count("return_records")).isOne();
        assertThat(rewardCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_items WHERE status='RETURNED'",
                Integer.class)).isOne();
    }

    @Test
    void mandatoryAuditFailureRollsBackEveryEarlierMutation() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture fixture = fixture(manager);
        jdbc.execute("""
                CREATE FUNCTION task8_fail_return_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced safe audit failure'; END;
                $$
                """);
        jdbc.execute("""
                CREATE TRIGGER task8_fail_return_audit
                BEFORE INSERT ON audit_logs FOR EACH ROW
                WHEN (NEW.target_type = 'RETURN_RECORD')
                EXECUTE FUNCTION task8_fail_return_audit()
                """);

        // When
        HttpResponse<String> response;
        try {
            response = post(manager, fixture.itemId(), fixture.reportId());
        } finally {
            jdbc.execute("DROP TRIGGER task8_fail_return_audit ON audit_logs");
            jdbc.execute("DROP FUNCTION task8_fail_return_audit()");
        }

        // Then
        assertError(response, 500, "COMMON-005");
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?", String.class,
                fixture.itemId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id=?", Boolean.class,
                fixture.reportId())).isFalse();
        assertThat(count("return_records")).isZero();
        assertThat(rewardCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_accounts WHERE user_id=?", Integer.class,
                fixture.finder().getId())).isZero();
        assertThat(auditCount()).isZero();
    }

    @Test
    void cleanMigrationCreatesThreeIndependentReturnUniquenessConstraints() {
        // Given / When
        List<String> constraints = jdbc.queryForList("""
                SELECT constraint_name
                FROM information_schema.table_constraints
                WHERE table_schema='public' AND table_name='return_records'
                  AND constraint_type='UNIQUE'
                ORDER BY constraint_name
                """, String.class);

        // Then
        assertThat(constraints).containsExactly(
                "uk_return_records_found_item",
                "uk_return_records_handover",
                "uk_return_records_lost_report");
    }

    @Test
    void controlledRegistrationWithdrawalVersusReturnKeepsOneReturnWinner() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture fixture = fixture(manager);
        CyclicBarrier barrier = new CyclicBarrier(2);
        String registration = """
                {"category":"WALLET","foundAt":"2026-08-23T08:00:00Z",
                 "foundLocation":{"latitude":37.5665,"longitude":126.9780},
                 "confirmedFeatures":{"color":"BLACK","publicDescription":"changed"},
                 "storageMethod":"LEFT_IN_PLACE","centerId":null,"storageDescription":null}
                """;

        // When
        HttpResponse<String> returned;
        HttpResponse<String> registrationResult;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> returnFuture = executor.submit(() -> {
                barrier.await();
                return post(manager, fixture.itemId(), fixture.reportId());
            });
            Future<HttpResponse<String>> registrationFuture = executor.submit(() -> {
                barrier.await();
                return request("PATCH", "/api/v1/found-items/" + fixture.itemId() + "/registration",
                        fixture.finder(), registration);
            });
            returned = returnFuture.get(15, TimeUnit.SECONDS);
            registrationResult = registrationFuture.get(15, TimeUnit.SECONDS);
        }

        // Then
        assertThat(returned.statusCode()).isEqualTo(201);
        assertThat(returned.body()).doesNotContain("finder", "email", "location", "private", "token", "metadata");
        if (registrationResult.statusCode() == 400) {
            assertError(registrationResult, 400, "COMMON-001");
        } else {
            assertError(registrationResult, 409, "STATE-001");
        }
        assertThat(registrationResult.body()).doesNotContain("email", "private", "metadata");
        assertThat(count("return_records")).isOne();
        assertThat(rewardCount()).isOne();
        assertDurableReturn(manager, fixture);
    }

    @Test
    void controlledHandoverConfirmationVersusReturnKeepsOneReturnWinner() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture fixture = fixture(manager);
        CyclicBarrier barrier = new CyclicBarrier(2);

        // When
        HttpResponse<String> returned;
        HttpResponse<String> confirmation;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> returnFuture = executor.submit(() -> {
                barrier.await();
                return post(manager, fixture.itemId(), fixture.reportId());
            });
            Future<HttpResponse<String>> confirmationFuture = executor.submit(() -> {
                barrier.await();
                return request("POST", "/api/v1/found-items/" + fixture.itemId() + ":confirm-handover",
                        fixture.finder(), null);
            });
            returned = returnFuture.get(15, TimeUnit.SECONDS);
            confirmation = confirmationFuture.get(15, TimeUnit.SECONDS);
        }

        // Then
        assertThat(returned.statusCode()).isEqualTo(201);
        assertError(confirmation, 409, "STATE-001");
        assertThat(count("return_records")).isOne();
        assertThat(rewardCount()).isOne();
    }

    @Test
    void controlledImagePersistenceVersusReturnHasNoPartialDurableState() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        Fixture fixture = fixture(manager);
        CyclicBarrier barrier = new CyclicBarrier(2);

        // When
        HttpResponse<String> returned;
        String imageResult;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<HttpResponse<String>> returnFuture = executor.submit(() -> {
                barrier.await();
                return post(manager, fixture.itemId(), fixture.reportId());
            });
            Future<String> imageFuture = executor.submit(() -> {
                barrier.await();
                try {
                    imagePersistence.commitUpload(fixture.itemId(), fixture.finder().getId(),
                            new FoundItemImagePersistenceService.PendingImage(
                                    "image.png", "<FAKE_OBJECT_KEY>", "image/png", 8, UUID.randomUUID()));
                    return "SAVED";
                } catch (LostoryException exception) {
                    return exception.getErrorCode().getCode();
                }
            });
            returned = returnFuture.get(15, TimeUnit.SECONDS);
            imageResult = imageFuture.get(15, TimeUnit.SECONDS);
        }

        // Then
        assertThat(returned.statusCode()).isEqualTo(201);
        assertThat(imageResult).isIn("SAVED", "COMMON-001");
        assertThat(count("return_records")).isOne();
        assertThat(rewardCount()).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?", String.class,
                fixture.itemId())).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM found_item_images WHERE found_item_id=?",
                Integer.class, fixture.itemId())).isIn(0, 1);
    }

    private Fixture fixture(User manager) {
        User finder = user(UserRole.USER);
        User owner = user(UserRole.USER);
        List<Long> existingCenters = jdbc.queryForList(
                "SELECT center_id FROM center_partnerships WHERE manager_user_id=? AND status='ACTIVE'",
                Long.class, manager.getId());
        Long centerId;
        if (existingCenters.isEmpty()) {
            centerId = center();
            activate(manager, centerId);
        } else {
            centerId = existingCenters.getFirst();
        }
        Long itemId = acceptedItem(finder.getId(), centerId);
        Long handoverId = jdbc.queryForObject("SELECT id FROM center_handovers WHERE found_item_id=?",
                Long.class, itemId);
        Long reportId = report(owner.getId());
        jdbc.update("""
                INSERT INTO match_candidates (report_id, item_id, rank, score, score_breakdown, created_at)
                VALUES (?, ?, 1, 99.00, '{}', now())
                """, reportId, itemId);
        return new Fixture(itemId, reportId, handoverId, finder, owner);
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@task8.example", "hash", "Fixture", role));
    }

    private Long center() {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, 'center', 'Seoul', ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                        '02-0000-0000', '09-18', 'official_verified', true, true, now(), now())
                RETURNING id
                """, Long.class, "task8:" + UUID.randomUUID());
    }

    private void activate(User manager, Long centerId) {
        jdbc.update("""
                INSERT INTO center_partnerships
                    (center_id, manager_email, manager_display_name, status, manager_user_id,
                     created_at, updated_at, activated_at)
                VALUES (?, ?, 'Manager', 'ACTIVE', ?, now(), now(), now())
                """, centerId, manager.getEmail(), manager.getId());
    }

    private Long acceptedItem(Long finderId, Long centerId) {
        Long itemId = jdbc.queryForObject("""
                INSERT INTO found_items
                    (finder_id, name, category, description, found_at, found_location, storage_method,
                     center_id, handover_status, handed_at, status, vision_status, analysis_generation,
                     expired_at, created_at, updated_at)
                VALUES (?, 'wallet', 'WALLET', 'safe public description', now() - interval '1 hour',
                        ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                        'HANDED_TO_CENTER', ?, 'CENTER_CONFIRMED', now() - interval '30 minutes',
                        'ACTIVE', 'READY', 1, now() + interval '14 days',
                        now() - interval '2 hours', now()) RETURNING id
                """, Long.class, finderId, centerId);
        jdbc.update("""
                INSERT INTO center_handovers
                    (found_item_id, center_id, status, user_confirmed_at, decided_at, decided_by, created_at)
                VALUES (?, ?, 'CENTER_CONFIRMED', now() - interval '30 minutes', now(),
                        (SELECT manager_user_id FROM center_partnerships WHERE center_id=? AND status='ACTIVE'),
                        now() - interval '30 minutes')
                """, itemId, centerId, centerId);
        return itemId;
    }

    private Long report(Long ownerId) {
        return jdbc.queryForObject("""
                INSERT INTO lost_reports
                    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
                     effective_search_radius_meters, radius_policy_version, center_guidance,
                     candidates_stale, last_matched_at, matching_policy_version, status,
                     expired_at, created_at, updated_at)
                VALUES (?, 'WALLET', now() - interval '3 hours', now() - interval '2 hours', 'wallet',
                        1000, 1000, 'p0-radius-v1', '[]', false, now(), 'p0-matching-v1', 'OPEN',
                        now() + interval '14 days', now(), now()) RETURNING id
                """, Long.class, ownerId);
    }

    private HttpResponse<String> post(User manager, Long itemId, Long reportId) throws Exception {
        return postRaw(manager, "{\"itemId\":\"%s\",\"reportId\":\"%s\"}"
                .formatted(itemId, reportId));
    }

    private HttpResponse<String> postRaw(User caller, String body) throws Exception {
        return request("POST", "/api/v1/dashboard/returns", caller, body);
    }

    private HttpResponse<String> request(String method, String path, User caller, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + tokens.issue(caller).value())
                .header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return http.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("Exception", "SQL", "finder", "location", "token");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int rewardCount() {
        return jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE entry_type='CENTER_RETURN_REWARD'",
                Integer.class);
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type='RETURN_RECORD'",
                Integer.class);
    }

    private void assertDurableReturn(User manager, Fixture fixture) {
        Long returnId = jdbc.queryForObject("""
                SELECT id FROM return_records
                WHERE handover_id=? AND found_item_id=? AND lost_report_id=?
                """, Long.class, fixture.handoverId(), fixture.itemId(), fixture.reportId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM point_ledger
                WHERE user_id=? AND entry_type='CENTER_RETURN_REWARD' AND amount=5
                  AND reference_type='FOUND_ITEM_RETURN' AND reference_id=?
                """, Integer.class, fixture.finder().getId(), returnId)).isOne();
        assertThat(jdbc.queryForObject("SELECT balance FROM point_accounts WHERE user_id=?", Integer.class,
                fixture.finder().getId())).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE user_id=? AND action='ITEM_RETURNED' AND target_type='RETURN_RECORD' AND target_id=?
                """, Integer.class, manager.getId(), returnId)).isOne();
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?", String.class,
                fixture.itemId())).isEqualTo("RETURNED");
        assertThat(jdbc.queryForObject("SELECT status FROM lost_reports WHERE id=?", String.class,
                fixture.reportId())).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id=?", Boolean.class,
                fixture.reportId())).isTrue();
    }

    private static final class ContentionGate {

        private final CountDownLatch reached;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger observed = new AtomicInteger();

        private ContentionGate(int parties) {
            this.reached = new CountDownLatch(parties);
        }

        private void reachAndAwaitRelease() throws InterruptedException {
            observed.incrementAndGet();
            reached.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("controlled contention gate was not released");
            }
        }

        private void assertBothTransactionsReachedAndRelease() throws InterruptedException {
            try {
                assertThat(reached.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(observed.get()).isEqualTo(2);
            } finally {
                release.countDown();
            }
        }
    }

    @TestConfiguration
    static class ContentionGateConfig {

        @Bean
        static BeanPostProcessor task8TransactionContentionGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!beanName.equals("foundItemRepository") && !beanName.equals("lostReportRepository")) {
                        return bean;
                    }
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        String method = invocation.getMethod().getName();
                        ContentionGate gate = beanName.equals("foundItemRepository")
                                && method.equals("findByIdForUpdate")
                                ? ACTIVE_ITEM_LOCK_GATE.get()
                                : beanName.equals("lostReportRepository")
                                        && method.equals("markOpenCandidatesStale")
                                        ? ACTIVE_STALE_GATE.get()
                                        : null;
                        if (gate != null) {
                            gate.reachAndAwaitRelease();
                        }
                        return invocation.proceed();
                    });
                    return proxy.getProxy();
                }
            };
        }
    }

    private record Fixture(Long itemId, Long reportId, Long handoverId, User finder, User owner) {
    }
}
