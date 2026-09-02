package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
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
    private static final AtomicReference<LockGate> ACTIVE_LOCK_GATE = new AtomicReference<>();

    @BeforeEach
    void reset() {
        ACTIVE_LOCK_GATE.set(null);
        jdbc.update("DELETE FROM center_handovers");
        jdbc.update("DELETE FROM center_activation_tokens");
        jdbc.update("DELETE FROM center_partnerships");
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
        assertThat(jdbc.queryForMap("""
                SELECT found_item_id, center_id, status, decided_at, superseded_at
                FROM center_handovers WHERE found_item_id = ?
                """, Long.valueOf(itemId)))
                .containsEntry("found_item_id", Long.valueOf(itemId))
                .containsEntry("center_id", Long.valueOf(centerId))
                .containsEntry("status", "USER_CONFIRMED")
                .containsEntry("decided_at", null)
                .containsEntry("superseded_at", null);
    }

    @Test
    void exactNoOpRegistrationPreservesCurrentHandover() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String centerId = insertCenter("official_verified", true, 126.9780, 37.5665);
        String itemId = confirmedItem(owner.getId(), token, centerId);
        Long handoverId = jdbc.queryForObject(
                "SELECT id FROM center_handovers WHERE found_item_id = ? AND superseded_at IS NULL",
                Long.class, Long.valueOf(itemId));

        // When
        HttpResponse<String> response = patch(itemId, token,
                registration("HANDED_TO_CENTER", centerId, null));

        // Then
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT id FROM center_handovers WHERE found_item_id = ? AND superseded_at IS NULL",
                Long.class, Long.valueOf(itemId))).isEqualTo(handoverId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_handovers WHERE found_item_id = ?",
                Integer.class, Long.valueOf(itemId))).isOne();
    }

    @Test
    void acceptedExactNoOpRegistrationPreservesCurrentHandover() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String centerId = insertCenter("official_verified", true, 126.9780, 37.5665);
        String itemId = confirmedItem(owner.getId(), token, centerId);
        Long handoverId = currentId(itemId);
        jdbc.update("""
                UPDATE center_handovers
                SET status='CENTER_CONFIRMED', decided_at=?, decided_by=? WHERE id=?
                """, Timestamp.from(SERVER_TIME), owner.getId(), handoverId);
        jdbc.update("UPDATE found_items SET handover_status='CENTER_CONFIRMED' WHERE id=?",
                Long.valueOf(itemId));
        Map<String, Object> before = state(itemId);

        // When
        HttpResponse<String> response = patch(itemId, token,
                registration("HANDED_TO_CENTER", centerId, null));

        // Then
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(state(itemId)).isEqualTo(before);
        assertThat(currentId(itemId)).isEqualTo(handoverId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_handovers WHERE found_item_id=?",
                Integer.class, Long.valueOf(itemId))).isOne();
    }

    @Test
    void rejectedHandoverCanBeResubmittedAndCreatesOneNewCurrentRecord() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String centerId = insertCenter("official_verified", true, 126.9780, 37.5665);
        String itemId = confirmedItem(owner.getId(), token, centerId);
        Long rejectedId = jdbc.queryForObject(
                "SELECT id FROM center_handovers WHERE found_item_id=? AND superseded_at IS NULL",
                Long.class, Long.valueOf(itemId));
        jdbc.update("""
                UPDATE center_handovers
                SET status='REJECTED', decided_at=?, decided_by=?, rejection_reason='not found'
                WHERE id=?
                """, Timestamp.from(SERVER_TIME), owner.getId(), rejectedId);

        // When
        HttpResponse<String> pending = patch(itemId, token,
                registration("HANDED_TO_CENTER", centerId, null));
        HttpResponse<String> confirmed = confirm(itemId, token, HttpRequest.BodyPublishers.noBody());

        // Then
        assertClaim(pending, "PENDING_HANDOVER", "NONE", centerId, true);
        assertClaim(confirmed, "ACTIVE", "USER_CONFIRMED", centerId, false);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM center_handovers
                WHERE found_item_id=? AND superseded_at IS NULL
                """, Integer.class, Long.valueOf(itemId))).isOne();
        assertThat(jdbc.queryForObject("SELECT superseded_at IS NOT NULL FROM center_handovers WHERE id=?",
                Boolean.class, rejectedId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_handovers WHERE found_item_id=?",
                Integer.class, Long.valueOf(itemId))).isEqualTo(2);
    }

    @Test
    void centerConfirmedHandoverBlocksRegistrationMutation() throws Exception {
        // Given
        User owner = user();
        String token = tokens.issue(owner).value();
        String centerId = insertCenter("official_verified", true, 126.9780, 37.5665);
        String itemId = confirmedItem(owner.getId(), token, centerId);
        Long handoverId = jdbc.queryForObject(
                "SELECT id FROM center_handovers WHERE found_item_id=? AND superseded_at IS NULL",
                Long.class, Long.valueOf(itemId));
        jdbc.update("""
                UPDATE center_handovers
                SET status='CENTER_CONFIRMED', decided_at=?, decided_by=? WHERE id=?
                """, Timestamp.from(SERVER_TIME), owner.getId(), handoverId);
        jdbc.update("UPDATE found_items SET handover_status='CENTER_CONFIRMED' WHERE id=?",
                Long.valueOf(itemId));
        Map<String, Object> before = state(itemId);

        // When
        HttpResponse<String> response = patch(itemId, token, registration("LEFT_IN_PLACE", null, null));

        // Then
        assertError(response, 409, "STATE-001");
        assertThat(state(itemId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_handovers WHERE found_item_id=?",
                Integer.class, Long.valueOf(itemId))).isOne();
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

    @Test
    void dashboardDecisionAndOwnerPatchRacesForceBothLockWinningOrders() throws Exception {
        // Given
        User owner = user();
        User manager = users.saveAndFlush(new User(UUID.randomUUID() + "@task7.example", "hash",
                "Manager", UserRole.CENTER_MANAGER));
        String ownerToken = tokens.issue(owner).value();
        String managerToken = tokens.issue(manager).value();
        String centerId = insertCenter("official_verified", true, 126.9780, 37.5665);
        jdbc.update("""
                INSERT INTO center_partnerships
                    (center_id, manager_email, manager_display_name, status, manager_user_id,
                     created_at, updated_at, activated_at)
                VALUES (?, ?, 'Manager', 'ACTIVE', ?, ?, ?, ?)
                """, Long.valueOf(centerId), manager.getEmail(), manager.getId(), Timestamp.from(SERVER_TIME),
                Timestamp.from(SERVER_TIME), Timestamp.from(SERVER_TIME));
        String acceptWinsItem = confirmedItem(owner.getId(), ownerToken, centerId);
        String patchBeatsAcceptItem = confirmedItem(owner.getId(), ownerToken, centerId);
        String rejectWinsItem = confirmedItem(owner.getId(), ownerToken, centerId);
        String patchBeatsRejectItem = confirmedItem(owner.getId(), ownerToken, centerId);
        Long acceptWinsHandover = currentId(acceptWinsItem);
        Long patchBeatsAcceptHandover = currentId(patchBeatsAcceptItem);
        Long rejectWinsHandover = currentId(rejectWinsItem);
        Long patchBeatsRejectHandover = currentId(patchBeatsRejectItem);
        Map<String, Object> acceptWinsP0 = registrationState(acceptWinsItem);
        Map<String, Object> rejectWinsP0 = registrationState(rejectWinsItem);
        String changedRegistration = registration("HANDED_TO_CENTER", centerId, null)
                .replace("검은 카드 지갑", "변경된 공개 설명");

        // When
        OrderedRace acceptWins = forceSecondRequestToWin(acceptWinsItem,
                () -> curl("PATCH", "/api/v1/found-items/" + acceptWinsItem + "/registration",
                        ownerToken, changedRegistration),
                () -> curl("POST", "/api/v1/dashboard/handovers/" + acceptWinsHandover + ":accept",
                        managerToken, "{\"privateFeatures\":[\"<REDACTED_PRIVATE_FEATURE>\"]}"));
        OrderedRace patchBeatsAccept = forceSecondRequestToWin(patchBeatsAcceptItem,
                () -> curl("POST", "/api/v1/dashboard/handovers/" + patchBeatsAcceptHandover + ":accept",
                        managerToken, "{\"privateFeatures\":[\"<REDACTED_PRIVATE_FEATURE>\"]}"),
                () -> curl("PATCH", "/api/v1/found-items/" + patchBeatsAcceptItem + "/registration",
                        ownerToken, changedRegistration));
        assertWinnerAndStateConflict(patchBeatsAccept);
        assertThat(registrationState(patchBeatsAcceptItem)).isEqualTo(patchBeatsAccept.winnerP0BeforeLoser());
        CurlResult acceptReconfirmation = curl("POST",
                "/api/v1/found-items/" + patchBeatsAcceptItem + ":confirm-handover", ownerToken, null);
        OrderedRace rejectWins = forceSecondRequestToWin(rejectWinsItem,
                () -> curl("PATCH", "/api/v1/found-items/" + rejectWinsItem + "/registration",
                        ownerToken, changedRegistration),
                () -> curl("POST", "/api/v1/dashboard/handovers/" + rejectWinsHandover + ":reject",
                        managerToken, "{\"reason\":\"not found\"}"));
        OrderedRace patchBeatsReject = forceSecondRequestToWin(patchBeatsRejectItem,
                () -> curl("POST", "/api/v1/dashboard/handovers/" + patchBeatsRejectHandover + ":reject",
                        managerToken, "{\"reason\":\"not found\"}"),
                () -> curl("PATCH", "/api/v1/found-items/" + patchBeatsRejectItem + "/registration",
                        ownerToken, changedRegistration));
        assertWinnerAndStateConflict(patchBeatsReject);
        assertThat(registrationState(patchBeatsRejectItem)).isEqualTo(patchBeatsReject.winnerP0BeforeLoser());
        CurlResult rejectReconfirmation = curl("POST",
                "/api/v1/found-items/" + patchBeatsRejectItem + ":confirm-handover", ownerToken, null);

        // Then
        assertWinnerAndStateConflict(acceptWins);
        assertThat(registrationState(acceptWinsItem)).isEqualTo(acceptWinsP0);
        assertCurrent(acceptWinsItem, acceptWinsHandover, "CENTER_CONFIRMED", 1);

        assertThat(acceptReconfirmation.status()).isEqualTo(200);
        assertSupersededThenReconfirmed(patchBeatsAcceptItem, patchBeatsAcceptHandover);

        assertWinnerAndStateConflict(rejectWins);
        assertThat(registrationState(rejectWinsItem)).isEqualTo(rejectWinsP0);
        assertCurrent(rejectWinsItem, rejectWinsHandover, "REJECTED", 1);

        assertThat(rejectReconfirmation.status()).isEqualTo(200);
        assertSupersededThenReconfirmed(patchBeatsRejectItem, patchBeatsRejectHandover);
    }

    @Test
    void v30BackfillsOnlyValidExplicitConfirmationAndEnforcesOneCurrentRow() {
        // Given
        try (PostgreSQLContainer postgres = new PostgreSQLContainer(
                DockerImageName.parse("postgis/postgis:16-3.5-alpine")
                        .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(dataSource).target(MigrationVersion.fromVersion("29")).load().migrate();
            JdbcTemplate migrationJdbc = new JdbcTemplate(dataSource);
            migrationJdbc.execute("""
                    INSERT INTO users
                        (id, email, password_hash, display_name, status, role, created_at, updated_at)
                    VALUES (900, 'migration@example.test', 'hash', 'User', 'ACTIVE', 'USER',
                            '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');
                    INSERT INTO lost_centers
                        (id, source_key, name, address, location, contact_phone, operating_hours,
                         verification_status, is_active,
                         is_csv_managed, created_at, updated_at)
                    VALUES (901, 'task7:migration', 'Center', 'Seoul',
                            ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                            '02-0000-0000', '09-18', 'official_verified', true,
                            true, '2026-08-01T00:00:00Z', '2026-08-01T00:00:00Z');
                    INSERT INTO found_items
                        (id, finder_id, name, category, description, found_at, storage_method,
                         center_id, handover_status, handed_at, status, vision_status,
                         analysis_generation, created_at, updated_at, expired_at)
                    VALUES (902, 900, 'wallet', 'WALLET', 'black', '2026-08-01T00:00:00Z',
                            'HANDED_TO_CENTER', 901, 'USER_CONFIRMED', '2026-08-01T01:00:00Z',
                            'ACTIVE', 'READY', 1, '2026-08-01T00:00:00Z',
                            '2026-08-01T01:00:00Z', '2026-08-15T00:00:00Z');
                    INSERT INTO found_items
                        (id, finder_id, name, category, description, found_at, storage_method,
                         legacy_handover_place_name, handover_status, status, vision_status,
                         analysis_generation, created_at, updated_at, expired_at)
                    VALUES (903, 900, 'legacy', 'OTHER', 'legacy', '2026-08-01T00:00:00Z',
                            'HANDED_TO_CENTER', 'legacy desk', 'LEGACY_UNVERIFIED', 'ACTIVE',
                            'FAILED', 0, '2026-08-01T00:00:00Z', '2026-08-01T01:00:00Z',
                            '2026-08-15T00:00:00Z');
                    """);

            // When
            Flyway.configure().dataSource(dataSource).load().migrate();

            // Then
            assertThat(migrationJdbc.queryForList(
                    "SELECT found_item_id || ':' || status FROM center_handovers ORDER BY found_item_id",
                    String.class)).containsExactly("902:USER_CONFIRMED");
            assertThatThrownBy(() -> migrationJdbc.update("""
                    INSERT INTO center_handovers
                        (found_item_id, center_id, status, user_confirmed_at, created_at)
                    VALUES (902, 901, 'USER_CONFIRMED', '2026-08-01T01:00:00Z',
                            '2026-08-01T01:00:00Z')
                    """)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    private User user() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@task8.example", "hash"));
    }

    private Long currentId(String itemId) {
        return jdbc.queryForObject("SELECT id FROM center_handovers WHERE found_item_id=? AND superseded_at IS NULL",
                Long.class, Long.valueOf(itemId));
    }

    private OrderedRace forceSecondRequestToWin(String itemId, Request blockedFirst, Request winner) throws Exception {
        LockGate gate = new LockGate(Long.valueOf(itemId));
        ACTIVE_LOCK_GATE.set(gate);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<CurlResult> blocked = executor.submit(blockedFirst::send);
            gate.awaitEntered();
            Future<CurlResult> winning = executor.submit(winner::send);
            CurlResult winnerResponse = winning.get(15, TimeUnit.SECONDS);
            Map<String, Object> winnerP0BeforeLoser = registrationState(itemId);
            gate.release();
            CurlResult blockedResponse = blocked.get(15, TimeUnit.SECONDS);
            return new OrderedRace(winnerResponse, blockedResponse, winnerP0BeforeLoser);
        } finally {
            gate.release();
            ACTIVE_LOCK_GATE.compareAndSet(gate, null);
        }
    }

    private void assertWinnerAndStateConflict(OrderedRace race) throws Exception {
        assertThat(race.winner().status()).isEqualTo(200);
        assertThat(race.blockedLoser().status()).isEqualTo(409);
        assertThat(mapper.readTree(race.blockedLoser().body()).get("code").asString()).isEqualTo("STATE-001");
    }

    private void assertCurrent(String itemId, Long expectedId, String status, int historyCount) {
        assertThat(jdbc.queryForMap("""
                SELECT id, status FROM center_handovers
                WHERE found_item_id=? AND superseded_at IS NULL
                """, Long.valueOf(itemId)))
                .containsEntry("id", expectedId)
                .containsEntry("status", status);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_handovers WHERE found_item_id=?",
                Integer.class, Long.valueOf(itemId))).isEqualTo(historyCount);
    }

    private void assertSupersededThenReconfirmed(String itemId, Long supersededId) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM center_handovers
                WHERE found_item_id=? AND superseded_at IS NULL
                """, Integer.class, Long.valueOf(itemId))).isOne();
        assertThat(jdbc.queryForMap("""
                SELECT status, superseded_at IS NOT NULL AS superseded
                FROM center_handovers WHERE id=?
                """, supersededId))
                .containsEntry("status", "USER_CONFIRMED")
                .containsEntry("superseded", true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM center_handovers WHERE found_item_id=?",
                Integer.class, Long.valueOf(itemId))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT handover_status FROM found_items WHERE id=?",
                String.class, Long.valueOf(itemId))).isEqualTo("USER_CONFIRMED");
    }

    private Map<String, Object> registrationState(String itemId) {
        return jdbc.queryForMap("""
                SELECT category, found_at, ST_AsText(found_location::geometry) AS found_location,
                       storage_method, storage_description, center_id, handed_at, status
                FROM found_items WHERE id=?
                """, Long.valueOf(itemId));
    }

    @FunctionalInterface
    private interface Request {
        CurlResult send() throws Exception;
    }

    private record OrderedRace(
            CurlResult winner,
            CurlResult blockedLoser,
            Map<String, Object> winnerP0BeforeLoser
    ) {
    }

    private record CurlResult(int status, String body) {
    }

    private static final class LockGate {
        private final Long itemId;
        private final AtomicBoolean first = new AtomicBoolean(true);
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private LockGate(Long itemId) {
            this.itemId = itemId;
        }

        private boolean blocks(Long candidateId) {
            if (itemId.equals(candidateId) && first.compareAndSet(true, false)) {
                entered.countDown();
                return true;
            }
            return false;
        }

        private void awaitEntered() throws InterruptedException {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void awaitRelease() throws InterruptedException {
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
        }

        private void release() {
            release.countDown();
        }
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

    private CurlResult curl(String method, String path, String token, String body) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(java.util.List.of(
                "curl", "-sS", "-i", "--max-time", "15",
                "-H", "Authorization: Bearer " + token,
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
        String statusLine = raw.substring(0, raw.indexOf("\r\n"));
        int status = Integer.parseInt(statusLine.split(" ")[1]);
        String responseBody = raw.substring(bodyStart + 4);
        System.out.println("HANDOVER_RACE_CURL_RAW_SANITIZED " + method + " " + path
                + "\n" + raw);
        return new CurlResult(status, responseBody);
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
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
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
        static BeanPostProcessor task7FoundItemLockGate() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!beanName.equals("foundItemRepository")) {
                        return bean;
                    }
                    ProxyFactory proxy = new ProxyFactory(bean);
                    proxy.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        if (invocation.getMethod().getName().equals("findByIdForUpdate")) {
                            LockGate gate = ACTIVE_LOCK_GATE.get();
                            Long itemId = (Long) invocation.getArguments()[0];
                            if (gate != null && gate.blocks(itemId)) {
                                gate.awaitRelease();
                            }
                        }
                        return invocation.proceed();
                    });
                    return proxy.getProxy();
                }
            };
        }

        @Bean
        @Primary
        Clock task8Clock() {
            return Clock.fixed(SERVER_TIME, ZoneOffset.UTC);
        }
    }
}
