package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.partner.application.PartnerActivationDeliveryCipher;
import kr.lostory.backend.partner.domain.PartnerActivationDeliveryRepository;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "partner.activation-base-url=https://app.example.test/partner-activation")
// allow: SIZE_OK — one indivisible raw-curl P1 journey plus its test-owned SQL fixtures and redactor.
class P1ManualHttpQaIntegrationTest {

	private static final Path TRANSCRIPT = Path.of(
		".omo/start-work/evidence/api-spec-missing-endpoints/task-10/remediation-r3/manual-evidence-hygiene/"
			+ "curl-transcript-redacted.txt");
	private static final Path CLEANUP = TRANSCRIPT.resolveSibling("curl-timeout-cleanup.txt");
	private static final Instant NOW = Instant.parse("2026-08-27T04:00:00Z");
	private static final String PRIVATE_FILENAME = "private-wallet-evidence.png";
	private static final String PRIVATE_OBJECT_KEY = "found-items/private-task10-object-key";
	private static final String PRIVATE_FEATURE = "serial-private-task10";
	@LocalServerPort int port;
	@Autowired JwtTokenService tokens;
	@Autowired UserRepository users;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	@Autowired PartnerActivationDeliveryRepository activationDeliveries;
	@Autowired PartnerActivationDeliveryCipher deliveryCipher;
	@MockitoBean ObjectStorage storage;
	@MockitoBean Clock clock;

	@AfterEach
	void cleanupFixture() {
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
		jdbc.update("DELETE FROM partner_activation_delivery_outbox");
		jdbc.update("DELETE FROM center_activation_tokens");
		jdbc.update("DELETE FROM center_partnerships");
		jdbc.update("DELETE FROM found_item_vision_jobs");
		jdbc.update("DELETE FROM object_deletion_outbox");
		jdbc.update("DELETE FROM found_item_images");
		jdbc.update("DELETE FROM item_features");
		jdbc.update("DELETE FROM found_items");
		jdbc.update("DELETE FROM vision_daily_admissions");
		jdbc.update("DELETE FROM lost_centers WHERE source_key LIKE 'task10:%'");
		jdbc.update("DELETE FROM users WHERE email LIKE 'p1-%@example.test'");
	}

	@Test
	void hungCurlIsForciblyDestroyedAtTheJavaDeadline() throws Exception {
		prepareArtifact(CLEANUP);
		long pid;
		try (ServerSocket server = new ServerSocket(0)) {
			Thread acceptor = Thread.ofVirtual().start(() -> holdConnection(server));
			Process process = new ProcessBuilder("curl", "-i", "--max-time", "15", "-sS",
				"http://127.0.0.1:" + server.getLocalPort() + "/hung").redirectErrorStream(true).start();
			pid = process.pid();
			try {
				assertThat(process.waitFor(50, TimeUnit.MILLISECONDS)).as("hung curl unexpectedly exited").isFalse();
			} finally {
				if (process.isAlive()) {
					process.destroyForcibly();
					assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("forced curl cleanup").isTrue();
				}
				acceptor.interrupt();
				acceptor.join(5_000);
				assertThat(acceptor.isAlive()).as("timeout acceptor cleanup").isFalse();
			}
		}
		assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
		Files.writeString(CLEANUP, "PASS curl -i --max-time 15; Java deadline=50ms; "
			+ "destroyForcibly=true; processAlive=false; acceptorTerminated=true\n", StandardCharsets.UTF_8);
	}

	@Test
	void rawCurlProvesTheP1JourneyWithRedactedEvidence() throws Exception {
		// Given
		prepareArtifact(TRANSCRIPT);
		User admin = user("admin", UserRole.ADMIN);
		User owner = user("owner", UserRole.USER);
		String finderEmail = "p1-finder-" + UUID.randomUUID() + "@example.test";
		String managerEmail = "p1-manager-" + UUID.randomUUID() + "@example.test";
		String adminToken = tokens.issue(admin).value();
		String ownerToken = tokens.issue(owner).value();
		UUID idempotencyKey = UUID.randomUUID();
		when(clock.instant()).thenReturn(NOW);
		List<Observed> observed = new ArrayList<>();

		// When
		Observed finderSignup = curl(new Request("finder-signup", "POST", "/api/v1/auth/signup", null, null,
			"{\"email\":\"%s\",\"password\":\"safe-password-123\",\"displayName\":\"P1 Finder\"}"
				.formatted(finderEmail)));
		User finder = users.findByEmail(finderEmail).orElseThrow();
		Observed finderLogin = curl(new Request("finder-login", "POST", "/api/v1/auth/login", null, null,
			"{\"email\":\"%s\",\"password\":\"safe-password-123\"}".formatted(finderEmail)));
		String finderToken = body(finderLogin).path("accessToken").asString();
		Long centerId = center();
		Long itemId = item(finder.getId(), centerId);
		Long handoverId = handover(itemId, centerId);
		Long reportId = report(owner.getId());
		jdbc.update("INSERT INTO match_candidates (report_id,item_id,rank,score,score_breakdown,created_at) "
			+ "VALUES (?,?,1,99.00,'{}',now())", reportId, itemId);
		jdbc.update("INSERT INTO point_accounts (user_id,balance) VALUES (?,10)", owner.getId());
		image(itemId);
		Observed finderBalanceBefore = curl(new Request("finder-balance-before-return", "GET",
			"/api/v1/points/balance", finderToken, null, null));
		Observed created = curl(new Request("partnership-create", "POST", "/api/v1/admin/partner-centers",
			adminToken, null, "{\"centerId\":\"%s\",\"manager\":{\"email\":\"%s\",\"displayName\":\"P1 Manager\"}}"
				.formatted(centerId, managerEmail)));
		Long partnershipId = body(created).path("partnershipId").asLong();
		Observed approved = curl(new Request("partnership-approve", "POST",
			"/api/v1/admin/partner-centers/" + partnershipId + ":approve", adminToken, null, null));
		String activationUrl = deliveryCipher.decrypt(activationDeliveries
			.findByPartnershipIdAndSupersededAtIsNull(partnershipId).orElseThrow());
		String capability = activationUrl.substring(activationUrl.lastIndexOf('/') + 1);
		Observed activated = curl(new Request("public-activation", "POST",
			"/api/v1/partner-manager-activations/" + capability, null, null,
			"{\"password\":\"safe-password-123\"}"));
		Observed activationReplay = curl(new Request("activation-replay", "POST",
			"/api/v1/partner-manager-activations/" + capability, null, null,
			"{\"password\":\"safe-password-123\"}"));
		User manager = users.findByEmail(managerEmail).orElseThrow();
		String managerToken = tokens.issue(manager).value();
		Observed signedImage = curl(new Request("signed-found-image", "GET",
			"/api/v1/found-items/" + itemId + "/image", finderToken, null, null));
		String signedUrl = body(signedImage).path("url").asString();
		String signedQuery = URI.create(signedUrl).getQuery();
		Observed handovers = curl(new Request("handover-list", "GET", "/api/v1/dashboard/handovers",
			managerToken, null, null));
		Observed accepted = curl(new Request("handover-accept", "POST",
			"/api/v1/dashboard/handovers/" + handoverId + ":accept", managerToken, null,
			"{\"privateFeatures\":[\"" + PRIVATE_FEATURE + "\"]}"));
		String accessPath = "/api/v1/lost-reports/" + reportId + "/candidate-accesses";
		Observed missingKey = curl(new Request("candidate-missing-key", "POST", accessPath, ownerToken, null, null));
		Observed badKey = curl(new Request("candidate-bad-key", "POST", accessPath, ownerToken, "bad", null));
		Observed firstAccess = curl(new Request("candidate-first", "POST", accessPath,
			ownerToken, idempotencyKey.toString(), null));
		Observed replayAccess = curl(new Request("candidate-replay", "POST", accessPath,
			ownerToken, idempotencyKey.toString(), null));
		String unlockedPath = "/api/v1/lost-reports/" + reportId + "/candidates/unlocked";
		Observed unlockedBefore = curl(new Request("unlocked-before-return", "GET", unlockedPath,
			ownerToken, null, null));
		String returnBody = "{\"itemId\":\"%s\",\"reportId\":\"%s\"}".formatted(itemId, reportId);
		Observed returned = curl(new Request("dashboard-return", "POST", "/api/v1/dashboard/returns",
			managerToken, null, returnBody));
		Observed finderBalanceAfterReturn = curl(new Request("finder-balance-after-return", "GET",
			"/api/v1/points/balance", finderToken, null, null));
		Observed returnReplay = curl(new Request("dashboard-return-replay", "POST", "/api/v1/dashboard/returns",
			managerToken, null, returnBody));
		Observed finderBalanceAfterReplay = curl(new Request("finder-balance-after-replay", "GET",
			"/api/v1/points/balance", finderToken, null, null));
		Observed unlockedAfter = curl(new Request("unlocked-after-return", "GET", unlockedPath,
			ownerToken, null, null));
		Observed ownerBalance = curl(new Request("owner-balance", "GET", "/api/v1/points/balance",
			ownerToken, null, null));
		Observed finderLedger = curl(new Request("finder-ledger", "GET", "/api/v1/points/ledger?page=1&pageSize=20",
			finderToken, null, null));
		Observed ownerLedger = curl(new Request("owner-ledger", "GET", "/api/v1/points/ledger?page=1&pageSize=20",
			ownerToken, null, null));
		jdbc.update("UPDATE users SET status='BLOCKED' WHERE id=?", finder.getId());
		Observed finderStaleTokenAfterBlock = curl(new Request("finder-stale-token-after-block", "GET",
			"/api/v1/users/me", finderToken, null, null));
		observed.addAll(List.of(finderSignup, finderLogin, finderBalanceBefore, created, approved, activated, activationReplay,
			signedImage, handovers, accepted, missingKey, badKey, firstAccess, replayAccess, unlockedBefore,
			returned, finderBalanceAfterReturn, returnReplay, finderBalanceAfterReplay, unlockedAfter,
			ownerBalance, finderLedger, ownerLedger, finderStaleTokenAfterBlock));

		// Then
		assertFields(finderSignup, 201, "id", "email", "displayName", "status", "roles");
		assertFields(finderLogin, 200, "accessToken", "tokenType", "expiresAt", "user");
		assertThat(body(finderLogin).path("accessToken").asString()).isNotBlank();
		assertThat(body(finderLogin).path("user").path("status").asString()).isEqualTo("ACTIVE");
		assertThat(body(finderBalanceBefore).path("balance").asInt()).isEqualTo(10);
		assertFields(created, 201, "partnershipId", "centerId", "status", "managerEmail");
		assertFields(approved, 200, "partnershipId", "status", "expiresAt");
		assertFields(activated, 200, "partnershipId", "centerId", "managerUserId", "status");
		assertError(activationReplay, 404, "COMMON-004");
		assertFields(signedImage, 200, "url", "expiresAt");
		assertThat(signedImage.header("cache-control")).isEqualTo("no-store");
		assertFields(handovers, 200, "data");
		assertFields(accepted, 200, "handoverId", "itemId", "handoverStatus", "acceptedAt");
		assertThat(accepted.body()).doesNotContain(PRIVATE_FEATURE, "privateFeatures");
		assertError(missingKey, 400, "COMMON-001");
		assertError(badKey, 400, "COMMON-001");
		assertFields(firstAccess, 200, "reportId", "unlockedAt", "debitedPoints", "remainingBalance", "replayed");
		assertThat(body(firstAccess).path("replayed").asBoolean()).isFalse();
		assertThat(body(replayAccess).path("replayed").asBoolean()).isTrue();
		assertFields(unlockedBefore, 200, "data");
		assertThat(unlockedBefore.body()).contains(itemId.toString(), "thumbnailUrl");
		assertFields(returned, 201, "returnId", "itemId", "reportId", "status", "rewardGranted");
		assertThat(body(finderBalanceAfterReturn).path("balance").asInt()).isEqualTo(15);
		assertThat(body(returnReplay)).isEqualTo(body(returned));
		assertThat(body(finderBalanceAfterReplay).path("balance").asInt()).isEqualTo(15);
		assertThat(unlockedAfter.body()).doesNotContain("\"itemId\":\"" + itemId + "\"");
		assertThat(body(ownerBalance).path("balance").asInt()).isEqualTo(9);
		JsonNode finderLedgerBody = body(finderLedger);
		assertThat(finderLedgerBody.path("meta").path("totalItems").asInt()).isEqualTo(2);
		assertThat(java.util.stream.StreamSupport.stream(finderLedgerBody.path("data").spliterator(), false)
			.filter(entry -> entry.path("type").asString().equals("CENTER_RETURN_REWARD")).count()).isOne();
		assertThat(java.util.stream.StreamSupport.stream(finderLedgerBody.path("data").spliterator(), false)
			.filter(entry -> entry.path("type").asString().equals("SIGNUP_GRANT")).count()).isOne();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM point_ledger WHERE user_id=? "
			+ "AND entry_type='CENTER_RETURN_REWARD'", Integer.class, finder.getId())).isOne();
		assertThat(ownerLedger.body()).contains("CANDIDATE_ACCESS_DEBIT");
		assertError(finderStaleTokenAfterBlock, 401, "AUTH-003");

		List<String> secrets = List.of(adminToken, finderToken, ownerToken, managerToken, activationUrl, capability,
			idempotencyKey.toString(), managerEmail, admin.getEmail(), finder.getEmail(), owner.getEmail(),
			PRIVATE_FILENAME, PRIVATE_OBJECT_KEY, PRIVATE_FEATURE, signedUrl, signedQuery);
		StringBuilder transcript = new StringBuilder();
		for (Observed response : observed) transcript.append(render(response));
		transcript.append("REPLAY_STATUS candidate-first=false; candidate-replay=true; dashboard-return=body-equality\n");
		Files.writeString(TRANSCRIPT, transcript, StandardCharsets.UTF_8);
		assertThat(secrets).allSatisfy(secret -> assertThat(transcript).doesNotContain(secret));
		assertThat(transcript).doesNotContain("https://", "Authorization: Bearer ey", PRIVATE_FEATURE,
			"safe-password-123", "HTTP/1.1")
			.contains("curl -i --max-time 15", "STATUS 200", "STATUS 201", "STATUS 400", "STATUS 404",
				"HEADER cache-control: no-store", "ERROR_CODE COMMON-001", "ERROR_CODE COMMON-004",
				"REPLAYED false", "REPLAYED true", "DATA_COUNT", "META_TOTAL_ITEMS");
		assertThat(transcript).contains("=== finder-login ===", "/api/v1/auth/login",
			"=== finder-stale-token-after-block ===", "STATUS 401", "ERROR_CODE AUTH-003");
	}

	private Observed curl(Request request) throws Exception {
		List<String> command = new ArrayList<>(List.of("curl", "-i", "--max-time", "15", "-sS"));
		if (request.token() != null) command.addAll(List.of("-H", "Authorization: Bearer " + request.token()));
		if (request.key() != null) command.addAll(List.of("-H", "Idempotency-Key: " + request.key()));
		if (!request.method().equals("GET")) command.addAll(List.of("-X", request.method()));
		if (request.body() != null) command.addAll(List.of("-H", "Content-Type: application/json", "--data", request.body()));
		command.add("http://127.0.0.1:" + port + request.path());
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String raw;
		try (ExecutorService reader = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			Future<byte[]> output = reader.submit(() -> process.getInputStream().readAllBytes());
			assertThat(process.waitFor(20, TimeUnit.SECONDS)).as(request.label() + " Java timeout").isTrue();
			assertThat(process.exitValue()).as(request.label() + " curl exit").isZero();
			raw = new String(output.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly();
				assertThat(process.waitFor(5, TimeUnit.SECONDS)).as(request.label() + " forced curl cleanup").isTrue();
			}
		}
		int separator = raw.lastIndexOf("\r\n\r\n");
		assertThat(separator).as(request.label() + " header/body separator").isPositive();
		String headers = raw.substring(0, separator);
		String statusLine = headers.substring(0, headers.indexOf("\r\n"));
		int status = Integer.parseInt(statusLine.split(" ")[1]);
		String responseBody = raw.substring(separator + 4);
		assertThat(header(headers, "content-type")).as(request.label() + " content-type")
			.startsWith("application/json");
		json.readTree(responseBody);
		return new Observed(request, status, headers, responseBody);
	}

	private void assertFields(Observed response, int status, String... fields) throws Exception {
		assertThat(response.status()).isEqualTo(status);
		assertThat(body(response).propertyNames()).containsExactlyInAnyOrder(fields);
	}

	private void assertError(Observed response, int status, String code) throws Exception {
		assertFields(response, status, "code", "message");
		assertThat(body(response).path("code").asString()).isEqualTo(code);
	}

	private JsonNode body(Observed response) throws Exception {
		return json.readTree(response.body());
	}

	private String header(String headers, String name) {
		return headers.lines().filter(line -> line.toLowerCase(Locale.ROOT).startsWith(name + ":"))
			.map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst().orElse("");
	}

	private String render(Observed observed) throws Exception {
		JsonNode response = body(observed);
		List<String> fields = new ArrayList<>(response.propertyNames());
		fields.sort(String::compareTo);
		StringBuilder summary = new StringBuilder("=== ").append(observed.request().label()).append(" ===\n")
			.append("curl -i --max-time 15 -sS");
		if (observed.request().token() != null) summary.append(" -H 'Authorization: Bearer <REDACTED>'");
		if (observed.request().key() != null) summary.append(" -H 'Idempotency-Key: <REDACTED_UUID>'");
		if (!observed.request().method().equals("GET")) summary.append(" -X ").append(observed.request().method());
		summary.append(" http://127.0.0.1:<RANDOM_PORT>").append(safePath(observed.request().path()));
		if (observed.request().body() != null) summary.append(" --data <REDACTED_BODY>");
		summary.append("\n");
		summary.append("STATUS ").append(observed.status()).append("\n")
			.append("FIELDS ").append(String.join(",", fields)).append("\n");
		if ("signed-found-image".equals(observed.request().label())
			&& "no-store".equals(observed.header("cache-control"))) summary.append("HEADER cache-control: no-store\n");
		if (response.has("code")) summary.append("ERROR_CODE ").append(response.path("code").asString()).append("\n");
		if (response.has("replayed")) summary.append("REPLAYED ").append(response.path("replayed").asBoolean()).append("\n");
		if (response.has("rewardGranted")) summary.append("REWARD_GRANTED ")
			.append(response.path("rewardGranted").asBoolean()).append("\n");
		if (response.path("data").isArray()) summary.append("DATA_COUNT ").append(response.path("data").size()).append("\n");
		if (response.path("meta").path("totalItems").canConvertToInt()) summary.append("META_TOTAL_ITEMS ")
			.append(response.path("meta").path("totalItems").asInt()).append("\n");
		return summary.append("\n").toString();
	}

	private String safePath(String path) {
		return path.replaceFirst("(/partner-manager-activations/)[^/]+", "$1<REDACTED_CAPABILITY>")
			.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
			"<REDACTED_UUID>").replaceAll("/[0-9]+(?=[:/]|$)", "/<REDACTED_DECIMAL_ID>");
	}

	private void prepareArtifact(Path artifact) throws Exception {
		Files.createDirectories(artifact.getParent());
		Files.deleteIfExists(artifact);
	}

	private User user(String label, UserRole role) {
		return users.saveAndFlush(new User("p1-" + label + "-" + UUID.randomUUID() + "@example.test",
			"hash", "P1 " + label, role));
	}

	private Long center() {
		return jdbc.queryForObject("INSERT INTO lost_centers (source_key,name,address,location,contact_phone,"
			+ "operating_hours,verification_status,is_active,is_csv_managed,created_at,updated_at) VALUES "
			+ "(?,'P1 Center','Seoul',ST_SetSRID(ST_MakePoint(126.978,37.5665),4326)::geography,"
			+ "'02-0000-0000','09-18','official_verified',true,false,now(),now()) RETURNING id",
			Long.class, "task10:" + UUID.randomUUID());
	}

	private Long item(Long finderId, Long centerId) {
		return jdbc.queryForObject("""
			INSERT INTO found_items
			    (finder_id,name,category,description,found_at,found_location,storage_method,center_id,
			     handover_status,handed_at,status,vision_status,analysis_generation,expired_at,created_at,updated_at)
			VALUES (?,'wallet','WALLET','public wallet',now()-interval '1 hour',
			        ST_SetSRID(ST_MakePoint(126.978,37.5665),4326)::geography,'HANDED_TO_CENTER',?,
			        'USER_CONFIRMED',now(),'ACTIVE','READY',1,now()+interval '14 days',now(),now()) RETURNING id
			""", Long.class, finderId, centerId);
	}

	private Long handover(Long itemId, Long centerId) {
		return jdbc.queryForObject("INSERT INTO center_handovers "
			+ "(found_item_id,center_id,status,user_confirmed_at,created_at) "
			+ "VALUES (?,?,'USER_CONFIRMED',now(),now()) RETURNING id", Long.class, itemId, centerId);
	}

	private Long report(Long ownerId) {
		Long id = jdbc.queryForObject("""
			INSERT INTO lost_reports
			    (reporter_id,category,lost_at_from,lost_at_to,description,search_radius,
			     effective_search_radius_meters,radius_policy_version,center_guidance,candidates_stale,
			     last_matched_at,matching_policy_version,status,expired_at,created_at,updated_at)
			VALUES (?,'WALLET',now()-interval '3 hours',now()-interval '2 hours','wallet',1000,1000,
			        'p0-radius-v1','[]',false,now(),'p0-matching-v1','OPEN',now()+interval '14 days',now(),now())
			RETURNING id
			""", Long.class, ownerId);
		jdbc.update("INSERT INTO report_waypoints (report_id,ordinal,location,created_at) VALUES "
			+ "(?,1,ST_SetSRID(ST_MakePoint(126.978,37.5665),4326)::geography,now())", id);
		return id;
	}

	private void image(Long itemId) {
		UUID operation = UUID.randomUUID();
		byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
		when(storage.head(PRIVATE_OBJECT_KEY)).thenReturn(Optional.of(new ObjectStorage.ObjectMetadata(
			PRIVATE_OBJECT_KEY, "image/png", png.length, operation, NOW)));
		when(storage.presignGet(eq(PRIVATE_OBJECT_KEY), any())).thenAnswer(invocation ->
			new ObjectStorage.PresignedGet(URI.create(
				"https://signed.example.test/private-image?X-Amz-Signature=private-query-task10"),
				invocation.getArgument(1)));
		jdbc.update("INSERT INTO found_item_images (found_item_id,original_filename,object_key,is_current,"
			+ "analysis_generation,upload_operation_id,content_type,size_bytes,created_at) "
			+ "VALUES (?,?,?,true,1,?,'image/png',?,now())",
			itemId, PRIVATE_FILENAME, PRIVATE_OBJECT_KEY, operation, png.length);
	}

	private void holdConnection(ServerSocket server) {
		try (Socket ignored = server.accept()) {
			Thread.sleep(10_000);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("timeout acceptor I/O failure", exception);
		}
	}

	private record Request(String label, String method, String path, String token, String key, String body) {
	}

	private record Observed(Request request, int status, String headers, String body) {
		String header(String name) {
			return headers.lines().filter(line -> line.toLowerCase(Locale.ROOT).startsWith(name + ":"))
				.map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst().orElse("");
		}
	}
}
