package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
class Task8ReturnManualHttpQaIntegrationTest {

    private static final Path TRANSCRIPT = Path.of(
            ".omo/start-work/evidence/api-spec-missing-endpoints/task-8/curl-transcript-redacted.txt");

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM audit_logs WHERE target_type = 'RETURN_RECORD'");
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
        jdbc.update("DELETE FROM found_item_images");
        jdbc.update("DELETE FROM item_features");
        jdbc.update("DELETE FROM found_items");
        jdbc.update("DELETE FROM lost_centers");
    }

    @Test
    void realCurlProvesReturnReplayPrivacyAuthorizationRollbackAndCandidateExclusion() throws Exception {
        // Given
        User manager = user(UserRole.CENTER_MANAGER);
        User foreign = user(UserRole.CENTER_MANAGER);
        User wrongRole = user(UserRole.USER);
        User finder = user(UserRole.USER);
        User owner = user(UserRole.USER);
        Long centerId = center();
        activate(manager, centerId);
        activate(foreign, center());
        Long itemId = acceptedItem(finder.getId(), centerId, manager.getId());
        Long reportId = report(owner.getId(), "OPEN");
        candidate(reportId, itemId);
        unlock(owner.getId(), reportId);
        Long rollbackItem = acceptedItem(user(UserRole.USER).getId(), centerId, manager.getId());
        Long closedReport = report(user(UserRole.USER).getId(), "CLOSED");
        candidate(closedReport, rollbackItem);
        User rejectedFinder = user(UserRole.USER);
        Long rejectedItem = acceptedItem(rejectedFinder.getId(), centerId, manager.getId());
        Long rejectedReport = report(user(UserRole.USER).getId(), "OPEN");
        candidate(rejectedReport, rejectedItem);
        jdbc.update("UPDATE center_handovers SET status='REJECTED', rejection_reason='not present' "
                + "WHERE found_item_id=?", rejectedItem);
        jdbc.update("UPDATE found_items SET handover_status='USER_CONFIRMED' WHERE id=?", rejectedItem);
        User mismatchFinder = user(UserRole.USER);
        Long mismatchItem = acceptedItem(mismatchFinder.getId(), centerId, manager.getId());
        Long mismatchReport = report(user(UserRole.USER).getId(), "OPEN");
        String managerToken = tokens.issue(manager).value();
        String foreignToken = tokens.issue(foreign).value();
        String wrongRoleToken = tokens.issue(wrongRole).value();
        String ownerToken = tokens.issue(owner).value();
        List<String> transcript = new ArrayList<>();

        // When
        CurlResult created = post(managerToken, payload(itemId, reportId));
        CurlResult replay = post(managerToken, payload(itemId, reportId));
        CurlResult foreignDenied = post(foreignToken, payload(itemId, reportId));
        CurlResult wrongRoleDenied = post(wrongRoleToken, payload(itemId, reportId));
        CurlResult malformed = post(managerToken, "{\"itemId\":\"bad\",\"reportId\":\"1\"}");
        CurlResult missing = post(managerToken, "{\"itemId\":\"1\"}");
        CurlResult closed = post(managerToken, payload(rollbackItem, closedReport));
        Boolean rejectedStaleBefore = jdbc.queryForObject(
                "SELECT candidates_stale FROM lost_reports WHERE id=?", Boolean.class, rejectedReport);
        Boolean mismatchStaleBefore = jdbc.queryForObject(
                "SELECT candidates_stale FROM lost_reports WHERE id=?", Boolean.class, mismatchReport);
        CurlResult rejected = post(managerToken, payload(rejectedItem, rejectedReport));
        CurlResult mismatch = post(managerToken, payload(mismatchItem, mismatchReport));
        CurlResult unlocked = get(ownerToken,
                "/api/v1/lost-reports/" + reportId + "/candidates/unlocked");
        CurlResult replayAfterRecompute = post(managerToken, payload(itemId, reportId));

        // Then
        assertStatusCode(created, 201, null);
        assertStatusCode(replay, 201, null);
        assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(created.body()));
        assertStatusCode(foreignDenied, 403, "COMMON-003");
        assertStatusCode(wrongRoleDenied, 403, "COMMON-003");
        assertStatusCode(malformed, 400, "COMMON-001");
        assertStatusCode(missing, 400, "COMMON-001");
        assertStatusCode(closed, 409, "REPORT_NOT_OPEN");
        assertStatusCode(rejected, 409, "STATE-001");
        assertStatusCode(mismatch, 409, "STATE-001");
        assertThat(unlocked.status()).isEqualTo(200);
        assertThat(replayAfterRecompute.status()).isEqualTo(201);
        assertThat(json.readTree(replayAfterRecompute.body())).isEqualTo(json.readTree(created.body()));
        JsonNode createdBody = json.readTree(created.body());
        assertThat(createdBody.propertyNames()).containsExactlyInAnyOrder(
                "returnId", "itemId", "reportId", "status", "rewardGranted");
        assertThat(createdBody.get("rewardGranted").asInt()).isEqualTo(5);
        assertThat(unlocked.body()).doesNotContain(itemId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_records", Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM point_ledger WHERE entry_type='CENTER_RETURN_REWARD' AND amount=5",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT balance FROM point_accounts WHERE user_id=?",
                Integer.class, finder.getId())).isEqualTo(5);
        Long returnId = createdBody.get("returnId").asLong();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE user_id=? AND action='ITEM_RETURNED' AND target_type='RETURN_RECORD' AND target_id=?
                  AND metadata_json=jsonb_build_object('actionVersion', 1, 'resourceId', ?::bigint)
                """, Integer.class, manager.getId(), returnId, returnId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type='RETURN_RECORD'", Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT metadata_json::text FROM audit_logs WHERE target_type='RETURN_RECORD'", String.class))
                .doesNotContain("finder", "email", "location", "token", "private", "object");
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?",
                String.class, rollbackItem)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id=?",
                Boolean.class, closedReport)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?",
                String.class, rejectedItem)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id=?",
                Boolean.class, rejectedReport)).isEqualTo(rejectedStaleBefore);
        assertThat(jdbc.queryForObject("SELECT status FROM found_items WHERE id=?",
                String.class, mismatchItem)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT candidates_stale FROM lost_reports WHERE id=?",
                Boolean.class, mismatchReport)).isEqualTo(mismatchStaleBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM point_accounts WHERE user_id IN (?, ?)",
                Integer.class, rejectedFinder.getId(), mismatchFinder.getId())).isZero();

        add(transcript, "CREATE", created, itemId, reportId);
        add(transcript, "REPLAY", replay, itemId, reportId);
        add(transcript, "CROSS_CENTER", foreignDenied, itemId, reportId);
        add(transcript, "WRONG_ROLE", wrongRoleDenied, itemId, reportId);
        add(transcript, "MALFORMED", malformed, itemId, reportId);
        add(transcript, "MISSING_ID", missing, itemId, reportId);
        add(transcript, "CLOSED_ROLLBACK", closed, rollbackItem, closedReport);
        add(transcript, "REJECTED_HANDOVER_ROLLBACK", rejected, rejectedItem, rejectedReport);
        add(transcript, "MISMATCHED_RELATION_ROLLBACK", mismatch, mismatchItem, mismatchReport);
        add(transcript, "UNLOCKED_AFTER_RETURN", unlocked, itemId, reportId);
        add(transcript, "REPLAY_AFTER_RECOMPUTE", replayAfterRecompute, itemId, reportId);
        Files.createDirectories(TRANSCRIPT.getParent());
        Files.writeString(TRANSCRIPT, String.join("\n", transcript), StandardCharsets.UTF_8);
        String saved = Files.readString(TRANSCRIPT);
        assertThat(saved).contains("HTTP/1.1 201", "<DECIMAL_ITEM_ID>", "<DECIMAL_REPORT_ID>")
                .doesNotContain(managerToken, foreignToken, wrongRoleToken, ownerToken,
                        manager.getEmail(), foreign.getEmail(), finder.getEmail(), owner.getEmail(),
                        rejectedFinder.getEmail(), mismatchFinder.getEmail());
    }

    private CurlResult post(String token, String body) throws Exception {
        return run(List.of("curl", "-sS", "-i", "--max-time", "15", "-X", "POST",
                "http://127.0.0.1:" + port + "/api/v1/dashboard/returns",
                "-H", "Authorization: Bearer " + token,
                "-H", "Content-Type: application/json", "--data", body));
    }

    private CurlResult get(String token, String path) throws Exception {
        return run(List.of("curl", "-sS", "-i", "--max-time", "15",
                "http://127.0.0.1:" + port + path, "-H", "Authorization: Bearer " + token));
    }

    private CurlResult run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean exited = process.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
        }
        assertThat(exited).isTrue();
        assertThat(process.exitValue()).isZero();
        String raw = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int separator = raw.indexOf("\r\n\r\n");
        return new CurlResult(Integer.parseInt(raw.substring(raw.indexOf(' ') + 1, raw.indexOf(' ') + 4)),
                raw.substring(separator + 4), raw);
    }

    private void add(List<String> transcript, String label, CurlResult result, Long itemId, Long reportId) {
        String sanitized = result.raw()
                .replace("\"itemId\":\"" + itemId + "\"", "\"itemId\":\"<DECIMAL_ITEM_ID>\"")
                .replace("\"reportId\":\"" + reportId + "\"", "\"reportId\":\"<DECIMAL_REPORT_ID>\"")
                .replaceAll("\"returnId\":\"[0-9]+\"", "\"returnId\":\"<DECIMAL_RETURN_ID>\"")
                .replaceAll("(?im)^date:.*$", "Date: <REDACTED>");
        transcript.add("=== " + label + " ===\n"
                + "Authorization: Bearer <REDACTED_TOKEN>\n"
                + "itemId=<DECIMAL_ITEM_ID> reportId=<DECIMAL_REPORT_ID>\n" + sanitized);
    }

    private void assertStatusCode(CurlResult result, int status, String code) throws Exception {
        assertThat(result.status()).isEqualTo(status);
        if (code != null) {
            assertThat(json.readTree(result.body()).get("code").asString()).isEqualTo(code);
        }
        assertThat(result.body()).doesNotContain("Exception", "SQL", "finderId", "location", "token");
    }

    private String payload(Long itemId, Long reportId) {
        return "{\"itemId\":\"%s\",\"reportId\":\"%s\"}".formatted(itemId, reportId);
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@task8-curl.example", "hash", "Fixture", role));
    }

    private Long center() {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers
                    (source_key, name, address, location, contact_phone, operating_hours,
                     verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, 'center', 'Seoul', ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                        '02-0000-0000', '09-18', 'official_verified', true, true, now(), now())
                RETURNING id
                """, Long.class, "task8-curl:" + UUID.randomUUID());
    }

    private void activate(User manager, Long centerId) {
        jdbc.update("""
                INSERT INTO center_partnerships
                    (center_id, manager_email, manager_display_name, status, manager_user_id,
                     created_at, updated_at, activated_at)
                VALUES (?, ?, 'Manager', 'ACTIVE', ?, now(), now(), now())
                """, centerId, manager.getEmail(), manager.getId());
    }

    private Long acceptedItem(Long finderId, Long centerId, Long managerId) {
        Long itemId = jdbc.queryForObject("""
                INSERT INTO found_items
                    (finder_id, name, category, description, found_at, found_location, storage_method,
                     center_id, handover_status, handed_at, status, vision_status, analysis_generation,
                     expired_at, created_at, updated_at)
                VALUES (?, 'wallet', 'WALLET', 'public', now() - interval '1 hour',
                        ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
                        'HANDED_TO_CENTER', ?, 'CENTER_CONFIRMED', now() - interval '30 minutes',
                        'ACTIVE', 'READY', 1, now() + interval '14 days',
                        now() - interval '2 hours', now()) RETURNING id
                """, Long.class, finderId, centerId);
        jdbc.update("""
                INSERT INTO center_handovers
                    (found_item_id, center_id, status, user_confirmed_at, decided_at, decided_by, created_at)
                VALUES (?, ?, 'CENTER_CONFIRMED', now() - interval '30 minutes', now(), ?, now())
                """, itemId, centerId, managerId);
        return itemId;
    }

    private Long report(Long ownerId, String status) {
        return jdbc.queryForObject("""
                INSERT INTO lost_reports
                    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
                     effective_search_radius_meters, radius_policy_version, center_guidance,
                     candidates_stale, last_matched_at, matching_policy_version, status,
                     expired_at, created_at, updated_at)
                VALUES (?, 'WALLET', now() - interval '3 hours', now() - interval '2 hours', 'wallet',
                        1000, 1000, 'p0-radius-v1', '[]', false, now(), 'p0-matching-v1', ?,
                        now() + interval '14 days', now(), now()) RETURNING id
                """, Long.class, ownerId, status);
    }

    private void candidate(Long reportId, Long itemId) {
        jdbc.update("""
                INSERT INTO match_candidates (report_id, item_id, rank, score, score_breakdown, created_at)
                VALUES (?, ?, 1, 99.00, '{}', now())
                """, reportId, itemId);
    }

    private void unlock(Long ownerId, Long reportId) {
        jdbc.update("INSERT INTO point_accounts (user_id, balance) VALUES (?, 9)", ownerId);
        Long debitId = jdbc.queryForObject("""
                INSERT INTO point_ledger
                    (user_id, entry_type, amount, idempotency_key, reference_type, reference_id, created_at)
                VALUES (?, 'CANDIDATE_ACCESS_DEBIT', -1, ?, 'LOST_REPORT', ?, now()) RETURNING id
                """, Long.class, ownerId, UUID.randomUUID(), reportId);
        jdbc.update("""
                INSERT INTO candidate_accesses (report_id, user_id, debit_transaction_id, unlocked_at)
                VALUES (?, ?, ?, now())
                """, reportId, ownerId, debitId);
    }

    private record CurlResult(int status, String body, String raw) {
    }
}
