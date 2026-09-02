package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.founditem.application.FoundItemImageService;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, FoundItemObjectStorageIntegrationTest.StorageTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Task9ManualHttpQaIntegrationTest {

	@LocalServerPort private int port;
	@Autowired private UserRepository users;
	@Autowired private JwtTokenService tokens;
	@Autowired private ObjectMapper json;
	@Autowired private FoundItemRepository foundItems;
	@Autowired private FoundItemImageService images;
	@Autowired private Clock clock;
	@Autowired private JdbcTemplate jdbc;

	@Test
	void realCurlProvesOpenApiRolesPublicAndRetiredRoutes() throws Exception {
		User user = save(UserRole.USER);
		User admin = save(UserRole.ADMIN);
		User manager = save(UserRole.CENTER_MANAGER);
		Long managerCenterId = jdbc.queryForObject("""
			INSERT INTO lost_centers
			    (source_key, name, address, location, contact_phone, operating_hours,
			     verification_status, is_active, is_csv_managed, created_at, updated_at)
			VALUES (?, 'Manual Contract Center', 'Seoul',
			        ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography,
			        '02-0000-0000', '09-18', 'official_verified', true, false, now(), now())
			RETURNING id
			""", Long.class, "task9-manual:" + UUID.randomUUID());
		jdbc.update("""
			INSERT INTO center_partnerships
			    (center_id, manager_email, manager_display_name, status, manager_user_id,
			     created_at, updated_at, activated_at)
			VALUES (?, ?, 'Manual Manager', 'ACTIVE', ?, now(), now(), now())
			""", managerCenterId, manager.getEmail(), manager.getId());
		FoundItem imageItem = foundItems.saveAndFlush(new FoundItem(
			user.getId(), "Wallet", "WALLET_CARD", "Contract image fixture", Instant.now(),
			new BigDecimal("37.5"), new BigDecimal("127.0"), "Seoul", "Desk",
			StorageMethod.LEFT_IN_PLACE, null, null));
		images.upload(imageItem.getId(), user.getId(), new MockMultipartFile(
			"image", "wallet.png", "image/png",
			new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1}));

		CurlResult docs = curl("openapi", "GET", "/v3/api-docs", null, null);
		Set<String> inventory = operationInventory(docs.body());
		assertThat(docs.status()).isEqualTo(200);
		assertThat(inventory).containsExactlyElementsOf(ApiContractMatrix.OPERATIONS.stream()
			.map(ApiContractMatrix.Operation::key)
			.collect(java.util.stream.Collectors.toCollection(TreeSet::new)));
		System.out.println("CURL_OPENAPI_MATRIX operationCount=" + inventory.size()
			+ " exact=true operations=" + inventory);

		assertFields(curl("public-signup-valid", "POST", "/api/v1/auth/signup", null,
			"{\"email\":\"task9-manual-" + UUID.randomUUID() + "@example.test\","
				+ "\"password\":\"safe-password-123\",\"displayName\":\"Manual Contract\"}"),
			201, Set.of("id", "email", "displayName", "status", "roles"));
		assertFields(curl("user-profile", "GET", "/api/v1/users/me", tokens.issue(user).value(), null),
			200, Set.of("id", "email", "displayName", "status", "roles"));
		assertFields(curl("admin-center-valid", "POST", "/api/v1/admin/lost-centers",
			tokens.issue(admin).value(),
			"{\"name\":\"Curl Contract Center\",\"address\":\"Seoul\","
				+ "\"contactPhone\":\"02-1000-1000\",\"location\":{\"latitude\":37.5,\"longitude\":127.0}}"),
			201, Set.of("id", "name", "address", "contactPhone", "location", "isActive"));
		assertFields(curl("manager-dashboard-valid", "GET", "/api/v1/dashboard/handovers?status=USER_CONFIRMED",
			tokens.issue(manager).value(), null), 200, Set.of("data"));
		for (String path : List.of("/api/v1/found-items/1/images", "/api/v1/nearby-lost-centers")) {
			assertError(curl("retired", "GET", path, tokens.issue(user).value(), null), 404, "COMMON-004");
		}
		assertError(curl("retired", "POST", "/api/v1/found-items", tokens.issue(user).value(), "{}"),
			404, "COMMON-004");
		CurlResult signed = curl("signed-image", "GET",
			"/api/v1/found-items/" + imageItem.getId() + "/image", tokens.issue(user).value(), null);
		assertFields(signed, 200, Set.of("url", "expiresAt"));
		assertThat(signed.cacheControl()).isEqualTo("no-store");
		long ttlSeconds = Duration.between(clock.instant(), Instant.parse(signed.body().path("expiresAt").asString()))
			.getSeconds();
		assertThat(ttlSeconds).isEqualTo(300);
		System.out.println("SIGNED_IMAGE_ASSERT fields=[expiresAt, url] cacheControl=no-store TTL_SECONDS=" + ttlSeconds
			+ " url=<REDACTED_SIGNED_URL>");
		assertError(curl("retired-plural-image-post", "POST",
			"/api/v1/found-items/" + imageItem.getId() + "/images", tokens.issue(user).value(), null),
			404, "COMMON-004");
		assertError(curl("malformed-decimal-path", "GET",
			"/api/v1/found-items/not-decimal/image", tokens.issue(user).value(), null),
			400, "COMMON-001");
	}

	private User save(UserRole role) {
		return users.saveAndFlush(new User(UUID.randomUUID() + "@task9.invalid", "hash", "Task9", role));
	}

	private CurlResult curl(String scenario, String method, String path, String token, String body) throws Exception {
		List<String> command = new ArrayList<>(List.of("curl", "-sS", "-i", "--max-time", "15", "-X", method));
		if (token != null) command.addAll(List.of("-H", "Authorization: Bearer " + token));
		if (body != null) command.addAll(List.of("-H", "Content-Type: application/json", "--data", body));
		command.add("http://127.0.0.1:" + port + path);
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String raw;
		try {
			raw = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			assertThat(process.waitFor(15, TimeUnit.SECONDS)).as(scenario + " curl timeout").isTrue();
		} finally {
			if (process.isAlive()) process.destroyForcibly();
		}
		assertThat(process.exitValue()).as(scenario + " curl exit").isZero();
		int split = raw.lastIndexOf("\r\n\r\n");
		assertThat(split).as(scenario + " response split").isPositive();
		String headers = raw.substring(0, split);
		String statusLine = headers.substring(0, headers.indexOf("\r\n"));
		JsonNode responseBody = json.readTree(raw.substring(split + 4));
		String sanitizedBody = responseBody.toString();
		if (scenario.equals("user-profile")) {
			sanitizedBody = "{\"id\":\"<REDACTED_ID>\",\"email\":\"<REDACTED_EMAIL>\","
				+ "\"displayName\":\"<REDACTED_DISPLAY_NAME>\",\"status\":\"ACTIVE\",\"roles\":[\"USER\"]}";
		}
		if (scenario.equals("public-signup-valid")) {
			sanitizedBody = "{\"id\":\"<REDACTED_ID>\",\"email\":\"<REDACTED_EMAIL>\","
				+ "\"displayName\":\"<REDACTED_DISPLAY_NAME>\",\"status\":\"ACTIVE\",\"roles\":[\"USER\"]}";
		}
		if (scenario.equals("admin-center-valid")) {
			sanitizedBody = "{\"id\":\"<REDACTED_ID>\",\"name\":\"<REDACTED_NAME>\","
				+ "\"address\":\"<REDACTED_ADDRESS>\",\"contactPhone\":\"<REDACTED_PHONE>\","
				+ "\"location\":\"<REDACTED_LOCATION>\",\"isActive\":true}";
		}
		if (scenario.equals("signed-image")) {
			sanitizedBody = "{\"url\":\"<REDACTED_SIGNED_URL>\",\"expiresAt\":\"<REDACTED_EXPIRY>\"}";
		}
		String sanitizedPath = path.replaceAll("/found-items/[1-9][0-9]*/", "/found-items/<DECIMAL_ITEM_ID>/");
		System.out.println("CURL_RAW_REDACTED scenario=" + scenario + " command=curl -sS -i --max-time 15 "
			+ method + " " + sanitizedPath + (token == null ? "" : " Authorization: Bearer <REDACTED_BEARER>") + "\n"
			+ statusLine + "\nContent-Type: " + header(headers, "content-type")
			+ "\nCache-Control: " + header(headers, "cache-control") + "\n\n"
			+ (scenario.equals("openapi") ? "<OPENAPI_BODY_PARSED_BELOW>" : sanitizedBody));
		return new CurlResult(Integer.parseInt(statusLine.split(" ")[1]),
			header(headers, "cache-control"), responseBody);
	}

	private void assertError(CurlResult response, int status, String code) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.body().propertyNames()).containsExactlyInAnyOrderElementsOf(ApiContractMatrix.errorFields());
		assertThat(response.body().path("code").asString()).isEqualTo(code);
	}

	private void assertFields(CurlResult response, int status, Set<String> fields) {
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.body().propertyNames()).containsExactlyInAnyOrderElementsOf(fields);
	}

	private String header(String headers, String name) {
		return headers.lines().filter(line -> line.toLowerCase(java.util.Locale.ROOT).startsWith(name + ":"))
			.map(line -> line.substring(line.indexOf(':') + 1).trim()).findFirst().orElse("");
	}

	private Set<String> operationInventory(JsonNode api) {
		Set<String> operations = new TreeSet<>();
		api.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method ->
			operations.add(method.getKey().toUpperCase() + " " + path.getKey())));
		return operations;
	}

	private record CurlResult(int status, String cacheControl, JsonNode body) {
	}
}
