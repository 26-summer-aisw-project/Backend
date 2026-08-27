package kr.lostory.backend;

import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class ApiContractSuccessFixture {

	private static final byte[] PNG = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1};
	private final int port;
	private final JwtTokenService tokens;
	private final UserRepository users;
	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	ApiContractSuccessFixture(int port, JwtTokenService tokens, UserRepository users,
			JdbcTemplate jdbc, ObjectMapper json) {
		this.port = port;
		this.tokens = tokens;
		this.users = users;
		this.jdbc = jdbc;
		this.json = json;
	}

	Context seed() {
		Context context = new Context();
		context.signupEmail = "task9-signup-" + UUID.randomUUID() + "@example.test";
		context.partnerEmail = "task9-manager-" + UUID.randomUUID() + "@example.test";
		context.user = save(UserRole.USER);
		context.admin = save(UserRole.ADMIN);
		context.centerId = center();
		context.accessReportId = report(context.user.getId());
		context.returnReportId = report(save(UserRole.USER).getId());
		jdbc.update("INSERT INTO point_accounts (user_id, balance) VALUES (?, 10) ON CONFLICT (user_id) DO UPDATE SET balance=10",
			context.user.getId());
		return context;
	}

	HttpRequest request(ApiContractMatrix.Operation row, Context context) {
		return switch (row.key()) {
			case "POST /api/v1/auth/signup" -> json("POST", "/api/v1/auth/signup", null,
				"{\"email\":\"%s\",\"password\":\"safe-password-123\",\"displayName\":\"Task Nine\"}"
					.formatted(context.signupEmail));
			case "POST /api/v1/auth/login" -> json("POST", "/api/v1/auth/login", null,
				"{\"email\":\"%s\",\"password\":\"safe-password-123\"}".formatted(context.signupEmail));
			case "GET /api/v1/users/me" -> noBody("GET", "/api/v1/users/me", token(context.user));
			case "GET /api/v1/lost-centers" -> noBody("GET", "/api/v1/lost-centers?page=1&pageSize=20&q=Contract", token(context.user));
			case "GET /api/v1/lost-centers/nearby" -> noBody("GET", "/api/v1/lost-centers/nearby?latitude=37.5665&longitude=126.9780", token(context.user));
			case "POST /api/v1/admin/lost-centers" -> json("POST", "/api/v1/admin/lost-centers", token(context.admin),
				"{\"name\":\"Created Contract Center\",\"address\":\"Seoul\",\"contactPhone\":\"02-1000-1000\",\"location\":{\"latitude\":37.5665,\"longitude\":126.9780}}" );
			case "PATCH /api/v1/admin/lost-centers/{centerId}" -> json("PATCH", "/api/v1/admin/lost-centers/" + context.centerId,
				token(context.admin), "{\"contactPhone\":\"02-2000-2000\"}");
			case "POST /api/v1/found-items/drafts" -> multipart("POST", "/api/v1/found-items/drafts", token(context.user));
			case "GET /api/v1/found-items/{id}" -> noBody("GET", "/api/v1/found-items/" + context.foundItemId, token(context.user));
			case "GET /api/v1/found-items/{foundItemId}/image" -> noBody("GET", "/api/v1/found-items/" + context.foundItemId + "/image", token(context.user));
			case "PUT /api/v1/found-items/{foundItemId}/image" -> multipart("PUT", "/api/v1/found-items/" + context.foundItemId + "/image", token(context.user));
			case "GET /api/v1/found-items" -> noBody("GET", "/api/v1/found-items?page=1&pageSize=20&status=DRAFT", token(context.user));
			case "PATCH /api/v1/found-items/{id}/registration" -> json("PATCH", "/api/v1/found-items/" + context.foundItemId + "/registration",
				token(context.user), registration(context.centerId));
			case "GET /api/v1/found-items/{foundItemId}/nearby-centers" -> noBody("GET", "/api/v1/found-items/" + context.foundItemId + "/nearby-centers", token(context.user));
			case "POST /api/v1/found-items/{id}:confirm-handover" -> noBody("POST", "/api/v1/found-items/" + context.foundItemId + ":confirm-handover", token(context.user));
			case "POST /api/v1/lost-reports" -> json("POST", "/api/v1/lost-reports", token(context.user), lostReport());
			case "GET /api/v1/lost-reports" -> noBody("GET", "/api/v1/lost-reports?page=1&pageSize=20&status=OPEN", token(context.user));
			case "GET /api/v1/lost-reports/{reportId}" -> noBody("GET", "/api/v1/lost-reports/" + context.reportId, token(context.user));
			case "PATCH /api/v1/lost-reports/{reportId}" -> json("PATCH", "/api/v1/lost-reports/" + context.reportId,
				token(context.user), "{\"description\":\"updated contract wallet\"}");
			case "GET /api/v1/lost-reports/{reportId}/candidates" -> noBody("GET", "/api/v1/lost-reports/" + context.reportId + "/candidates", token(context.user));
			case "POST /api/v1/lost-reports/{reportId}:close" -> json("POST", "/api/v1/lost-reports/" + context.reportId + ":close", token(context.user), "{}");
			case "POST /api/v1/admin/partner-centers" -> json("POST", "/api/v1/admin/partner-centers", token(context.admin),
				"{\"centerId\":\"%s\",\"manager\":{\"email\":\"%s\",\"displayName\":\"Contract Manager\"}}"
					.formatted(context.centerId, context.partnerEmail));
			case "POST /api/v1/admin/partner-centers/{partnershipId}:approve" -> noBody("POST",
				"/api/v1/admin/partner-centers/" + context.partnershipId + ":approve", token(context.admin));
			case "POST /api/v1/partner-manager-activations/{activationToken}" -> json("POST",
				"/api/v1/partner-manager-activations/" + context.activationToken, null,
				"{\"password\":\"safe-password-123\"}");
			case "GET /api/v1/dashboard/handovers" -> noBody("GET", "/api/v1/dashboard/handovers?status=USER_CONFIRMED", token(context.manager));
			case "POST /api/v1/dashboard/handovers/{handoverId}:accept" -> json("POST",
				"/api/v1/dashboard/handovers/" + context.acceptHandoverId + ":accept", token(context.manager),
				"{\"privateFeatures\":[\"request-only-contract-check\"]}");
			case "POST /api/v1/dashboard/handovers/{handoverId}:reject" -> json("POST",
				"/api/v1/dashboard/handovers/" + context.rejectHandoverId + ":reject", token(context.manager),
				"{\"reason\":\"not present\"}");
			case "POST /api/v1/dashboard/returns" -> json("POST", "/api/v1/dashboard/returns", token(context.manager),
				"{\"itemId\":\"%s\",\"reportId\":\"%s\"}".formatted(context.foundItemId, context.returnReportId));
			case "POST /api/v1/lost-reports/{reportId}/candidate-accesses" -> header("POST",
				"/api/v1/lost-reports/" + context.accessReportId + "/candidate-accesses", token(context.user),
				"Idempotency-Key", context.idempotencyKey.toString());
			case "GET /api/v1/lost-reports/{reportId}/candidates/unlocked" -> noBody("GET",
				"/api/v1/lost-reports/" + context.accessReportId + "/candidates/unlocked", token(context.user));
			case "GET /api/v1/points/balance" -> noBody("GET", "/api/v1/points/balance", token(context.user));
			case "GET /api/v1/points/ledger" -> noBody("GET", "/api/v1/points/ledger?page=1&pageSize=20", token(context.user));
			default -> throw new IllegalArgumentException("Missing success fixture: " + row.key());
		};
	}

	void capture(ApiContractMatrix.Operation row, HttpResponse<String> response, Context context) throws Exception {
		JsonNode body = json.readTree(response.body());
		switch (row.key()) {
			case "POST /api/v1/found-items/drafts" -> context.foundItemId = body.path("id").asLong();
			case "POST /api/v1/found-items/{id}:confirm-handover" -> context.acceptHandoverId = jdbc.queryForObject(
				"SELECT id FROM center_handovers WHERE found_item_id=? AND superseded_at IS NULL", Long.class, context.foundItemId);
			case "POST /api/v1/lost-reports" -> context.reportId = body.path("id").asLong();
			case "POST /api/v1/admin/partner-centers" -> context.partnershipId = body.path("partnershipId").asLong();
			case "POST /api/v1/admin/partner-centers/{partnershipId}:approve" -> {
				String url = body.path("activationUrl").asString();
				context.activationToken = url.substring(url.lastIndexOf('/') + 1);
			}
			case "POST /api/v1/partner-manager-activations/{activationToken}" -> {
				context.manager = users.findById(body.path("managerUserId").asLong()).orElseThrow();
				context.rejectHandoverId = confirmedHandover(save(UserRole.USER).getId(), context.centerId);
				Long candidateItemId = jdbc.queryForObject(
					"SELECT found_item_id FROM center_handovers WHERE id=?", Long.class, context.rejectHandoverId);
				jdbc.update("INSERT INTO match_candidates (report_id, item_id, rank, score, score_breakdown, created_at) VALUES (?, ?, 1, 99.00, '{}', now())",
					context.returnReportId, context.foundItemId);
				jdbc.update("INSERT INTO match_candidates (report_id, item_id, rank, score, score_breakdown, created_at) VALUES (?, ?, 1, 88.00, '{}', now())",
					context.accessReportId, candidateItemId);
			}
			default -> { }
		}
	}

	HttpRequest wrongRole(ApiContractMatrix.Security security, Context context) {
		return switch (security) {
			case USER -> noBody("GET", "/api/v1/points/balance", token(context.manager));
			case ADMIN -> json("POST", "/api/v1/admin/lost-centers", token(context.user), "{}");
			case CENTER_MANAGER -> noBody("GET", "/api/v1/dashboard/handovers", token(context.user));
			case PUBLIC -> throw new IllegalArgumentException("Public operation has no wrong role");
		};
	}

	HttpRequest invalidPage(ApiContractMatrix.Operation row, Context context) {
		return switch (row.key()) {
			case "GET /api/v1/lost-centers" -> noBody("GET", "/api/v1/lost-centers?page=0&pageSize=20", token(context.user));
			case "GET /api/v1/found-items" -> noBody("GET", "/api/v1/found-items?page=0&pageSize=20", token(context.user));
			case "GET /api/v1/lost-reports" -> noBody("GET", "/api/v1/lost-reports?page=0&pageSize=20", token(context.user));
			case "GET /api/v1/points/ledger" -> noBody("GET", "/api/v1/points/ledger?page=0&pageSize=20", token(context.user));
			default -> throw new IllegalArgumentException("Missing page boundary fixture: " + row.key());
		};
	}

	HttpRequest invalidIdempotency(Context context) {
		return header("POST", "/api/v1/lost-reports/" + context.accessReportId + "/candidate-accesses",
			token(context.user), "Idempotency-Key", "not-a-uuid");
	}

	HttpRequest replay(Context context) {
		return header("POST", "/api/v1/lost-reports/" + context.accessReportId + "/candidate-accesses",
			token(context.user), "Idempotency-Key", context.idempotencyKey.toString());
	}

	HttpRequest malformedDecimal(ApiContractMatrix.Operation row, HttpRequest valid, String parameter) {
		String placeholder = "{" + parameter + "}";
		int marker = row.path().indexOf(placeholder);
		if (marker < 0 || row.decimalPathParameters().size() != 1) {
			throw new IllegalArgumentException("Invalid decimal binding: " + row.key() + " " + parameter);
		}
		String prefix = row.path().substring(0, marker);
		String suffix = row.path().substring(marker + placeholder.length());
		String actualPath = valid.uri().getPath();
		if (!actualPath.startsWith(prefix) || !actualPath.endsWith(suffix)) {
			throw new IllegalArgumentException("Fixture path does not bind matrix path: " + row.key());
		}
		String value = actualPath.substring(prefix.length(), actualPath.length() - suffix.length());
		if (!value.matches("[1-9][0-9]*")) {
			throw new IllegalArgumentException("Fixture did not use decimal " + parameter + ": " + row.key());
		}
		String query = valid.uri().getRawQuery();
		URI malformedUri = URI.create("http://127.0.0.1:" + port + prefix + "not-decimal" + suffix
			+ (query == null ? "" : "?" + query));
		HttpRequest.Builder malformed = HttpRequest.newBuilder(malformedUri);
		valid.headers().map().forEach((name, values) -> values.forEach(valuePart -> malformed.header(name, valuePart)));
		return malformed.method(valid.method(), valid.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
			.build();
	}

	HttpRequest nonEmptyCloseBody(Context context) {
		return json("POST", "/api/v1/lost-reports/" + context.reportId + ":close",
			token(context.user), "{\"unexpected\":true}");
	}

	HttpRequest malformedJson() {
		return json("POST", "/api/v1/auth/signup", null, "{");
	}

	HttpRequest missingMultipartImage(Context context) {
		return builder("/api/v1/found-items/" + context.foundItemId + "/image", token(context.user))
			.header("Content-Type", "multipart/form-data; boundary=empty")
			.PUT(HttpRequest.BodyPublishers.ofString("--empty--\r\n")).build();
	}

	private User save(UserRole role) {
		return users.saveAndFlush(new User(UUID.randomUUID() + "@task9-contract.example", "hash", "Contract", role));
	}

	private Long center() {
		return jdbc.queryForObject("""
			INSERT INTO lost_centers
			    (source_key, name, address, location, contact_phone, operating_hours,
			     verification_status, is_active, is_csv_managed, created_at, updated_at)
			VALUES (?, 'Contract Center', 'Seoul', ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
			        '02-0000-0000', '09-18', 'official_verified', true, false, now(), now()) RETURNING id
			""", Long.class, "task9:" + UUID.randomUUID());
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

	private Long confirmedHandover(Long ownerId, Long centerId) {
		Long itemId = jdbc.queryForObject("""
			INSERT INTO found_items
			    (finder_id, name, category, description, found_at, found_location, storage_method,
			     center_id, handover_status, handed_at, status, vision_status, analysis_generation,
			     expired_at, created_at, updated_at)
			VALUES (?, 'wallet', 'WALLET', 'public contract description', now() - interval '1 hour',
			        ST_SetSRID(ST_MakePoint(126.978, 37.5665), 4326)::geography,
			        'HANDED_TO_CENTER', ?, 'USER_CONFIRMED', now(), 'ACTIVE', 'READY', 1,
			        now() + interval '14 days', now(), now()) RETURNING id
			""", Long.class, ownerId, centerId);
		return jdbc.queryForObject("""
			INSERT INTO center_handovers (found_item_id, center_id, status, user_confirmed_at, created_at)
			VALUES (?, ?, 'USER_CONFIRMED', now(), now()) RETURNING id
			""", Long.class, itemId, centerId);
	}

	private String token(User user) {
		return tokens.issue(user).value();
	}

	private HttpRequest noBody(String method, String path, String token) {
		return builder(path, token).method(method, HttpRequest.BodyPublishers.noBody()).build();
	}

	private HttpRequest json(String method, String path, String token, String body) {
		return builder(path, token).header("Content-Type", "application/json")
			.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
	}

	private HttpRequest header(String method, String path, String token, String name, String value) {
		return builder(path, token).header(name, value).method(method, HttpRequest.BodyPublishers.noBody()).build();
	}

	private HttpRequest multipart(String method, String path, String token) {
		String boundary = "task9-" + UUID.randomUUID();
		byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"wallet.png\"\r\n"
			+ "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
		byte[] body = new byte[prefix.length + PNG.length + suffix.length];
		System.arraycopy(prefix, 0, body, 0, prefix.length);
		System.arraycopy(PNG, 0, body, prefix.length, PNG.length);
		System.arraycopy(suffix, 0, body, prefix.length + PNG.length, suffix.length);
		return builder(path, token).header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
	}

	private HttpRequest.Builder builder(String path, String token) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
		if (token != null) builder.header("Authorization", "Bearer " + token);
		return builder;
	}

	private String registration(Long centerId) {
		return """
			{"category":"WALLET","foundAt":"2026-08-23T08:00:00Z",
			 "foundLocation":{"latitude":37.5665,"longitude":126.9780},
			 "confirmedFeatures":{"color":"BLACK","publicDescription":"contract wallet"},
			 "storageMethod":"HANDED_TO_CENTER","centerId":"%s","storageDescription":null}
			""".formatted(centerId);
	}

	private String lostReport() {
		return """
			{"category":"WALLET","description":"contract wallet",
			 "lostAtFrom":"2026-08-23T07:00:00Z","lostAtTo":"2026-08-23T09:00:00Z",
			 "waypoints":[{"ordinal":1,"point":{"latitude":37.5665,"longitude":126.9780}}]}
			""";
	}

	static final class Context {
		String signupEmail;
		String partnerEmail;
		String activationToken;
		User user;
		User admin;
		User manager;
		Long centerId;
		Long foundItemId;
		Long reportId;
		Long accessReportId;
		Long returnReportId;
		Long partnershipId;
		Long acceptHandoverId;
		Long rejectHandoverId;
		UUID idempotencyKey = UUID.randomUUID();
	}
}
