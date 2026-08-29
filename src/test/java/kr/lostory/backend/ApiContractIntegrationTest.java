package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.GlobalExceptionHandler;
import kr.lostory.backend.partner.application.PartnerActivationDeliveryCipher;
import kr.lostory.backend.partner.domain.PartnerActivationDeliveryRepository;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.domain.UserRole;
import kr.lostory.backend.user.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockReset;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.core.JsonParser;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.core.json.JsonFactory;

@ActiveProfiles("test")
@Import({PostgresTestContainerConfig.class, FoundItemObjectStorageIntegrationTest.StorageTestConfig.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiContractIntegrationTest {

	private static final String NO_MATRIX_ERROR_CODE = "NONE";
	private static final String HANDLER_DIAGNOSTIC_ROW =
		"POST /api/v1/found-items/{id}:confirm-handover";
	private static final Set<String> MATRIX_HANDLER_CATEGORIES = Set.of(
		"DATA_ACCESS", "INVALID_ARGUMENT", "INVALID_STATE", "NOT_OBSERVED", "OTHER");
	private static final Set<String> MATRIX_ERROR_CODES = Arrays.stream(ErrorCode.values())
		.map(ErrorCode::getCode)
		.collect(java.util.stream.Collectors.toUnmodifiableSet());

	@LocalServerPort int port;
	@Autowired JwtTokenService tokens;
	@Autowired UserRepository users;
	@Autowired JdbcTemplate jdbc;
	@Autowired PartnerActivationDeliveryRepository activationDeliveries;
	@Autowired PartnerActivationDeliveryCipher deliveryCipher;
	@MockitoSpyBean(reset = MockReset.AFTER) GlobalExceptionHandler globalExceptionHandler;
	private final ObjectMapper json = new ObjectMapper();
	private final ObjectMapper matrixDiagnosticJson = new ObjectMapper(JsonFactory.builder()
		.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void everyMatrixRowHasAValidRoleAwareRealHttpSuccessFixture() throws Exception {
		AtomicReference<String> genericHandlerCategory = new AtomicReference<>("NOT_OBSERVED");
		AtomicReference<String> dataAccessSubtype = new AtomicReference<>();
		doAnswer(invocation -> {
			Exception exception = invocation.getArgument(0, Exception.class);
			genericHandlerCategory.set(matrixUnexpectedHandlerCategory(exception));
			dataAccessSubtype.set(matrixDataAccessSubtype(exception));
			return invocation.callRealMethod();
		}).when(globalExceptionHandler).handleUnexpectedException(any(Exception.class));
		assertThat(ApiContractMatrix.OPERATIONS).hasSize(32);
		ApiContractSuccessFixture fixtures = new ApiContractSuccessFixture(
			port, tokens, users, jdbc, json, activationDeliveries, deliveryCipher);
		ApiContractSuccessFixture.Context context = fixtures.seed();
		for (ApiContractMatrix.Operation row : ApiContractMatrix.OPERATIONS) {
			HttpRequest request = fixtures.request(row, context);
			genericHandlerCategory.set("NOT_OBSERVED");
			dataAccessSubtype.set(null);
			HttpResponse<String> response = httpClient.send(request,
				HttpResponse.BodyHandlers.ofString());
			String errorCode = matrixErrorCode(response.body());
			String handlerDiagnostic = row.key().equals(HANDLER_DIAGNOSTIC_ROW)
					&& row.successStatus() == 200 && response.statusCode() == 500
					&& errorCode.equals("COMMON-005")
				? " handler=" + genericHandlerCategory.get() : "";
			String subtypeDiagnostic = row.key().equals(HANDLER_DIAGNOSTIC_ROW)
					&& row.successStatus() == 200 && response.statusCode() == 500
					&& errorCode.equals("COMMON-005")
					&& genericHandlerCategory.get().equals("DATA_ACCESS")
					&& dataAccessSubtype.get() != null
				? " diagnostic={dataAccessSubtype=" + dataAccessSubtype.get() + "}" : "";
			assertThat(response.statusCode())
				.withFailMessage("matrix row %s expected HTTP %d actual HTTP %d code %s%s%s",
					row.key(), row.successStatus(), response.statusCode(), errorCode, handlerDiagnostic,
					subtypeDiagnostic)
				.isEqualTo(row.successStatus());
			JsonNode body = json.readTree(response.body());
			assertThat(body.propertyNames()).as(row.key() + " success fields")
				.containsExactlyInAnyOrderElementsOf(row.successFields());
			assertFlagOutputs(row, body);
			fixtures.capture(row, response, context);
			for (String decimalParameter : row.decimalPathParameters()) {
				HttpResponse<String> malformed = send(fixtures.malformedDecimal(row, request, decimalParameter));
				assertError(malformed, 400, "COMMON-001");
				System.out.println("MATRIX_DECIMAL_BOUNDARY key=" + row.key() + " parameter="
					+ decimalParameter + " valid=<DECIMAL_ID> malformed=400");
			}
			System.out.println("MATRIX_SUCCESS key=" + row.key() + " role=" + row.security()
				+ " status=" + response.statusCode() + " fields=" + new java.util.TreeSet<>(row.successFields()));
		}
		for (ApiContractMatrix.Security security : List.of(
			ApiContractMatrix.Security.USER, ApiContractMatrix.Security.ADMIN,
			ApiContractMatrix.Security.CENTER_MANAGER)) {
			HttpResponse<String> denied = httpClient.send(fixtures.wrongRole(security, context),
				HttpResponse.BodyHandlers.ofString());
			assertError(denied, 403, "COMMON-003");
		}
		for (ApiContractMatrix.Operation row : ApiContractMatrix.OPERATIONS) {
			if (row.flags().contains(ApiContractMatrix.Flag.PAGE)) {
				assertError(send(fixtures.invalidPage(row, context)), 400, "COMMON-001");
			}
		}
		assertError(send(fixtures.invalidIdempotency(context)), 400, "COMMON-001");
		assertError(send(fixtures.nonEmptyCloseBody(context)), 400, "COMMON-001");
		assertError(send(fixtures.malformedJson()), 400, "COMMON-001");
		assertError(send(fixtures.missingMultipartImage(context)), 400, "COMMON-001");

		ApiContractMatrix.Operation replayRow = ApiContractMatrix.OPERATIONS.stream()
			.filter(row -> row.flags().contains(ApiContractMatrix.Flag.REPLAY)).findFirst().orElseThrow();
		HttpResponse<String> replay = send(fixtures.replay(context));
		assertThat(replay.statusCode()).isEqualTo(replayRow.successStatus());
		JsonNode replayBody = json.readTree(replay.body());
		assertThat(replayBody.propertyNames()).containsExactlyInAnyOrderElementsOf(replayRow.successFields());
		assertThat(replayBody.path("replayed").asBoolean()).isTrue();
		System.out.println("MATRIX_BOUNDARIES pageRows=4 decimalRows=16 idempotency=400 emptyJson=400"
			+ " malformedJson=400 multipart=400 replayed=true wrongRoles=[USER,ADMIN,CENTER_MANAGER]");
	}

	@Test
	void candidateOwnershipConcealsForeignUserAndAdminWithExactError() throws Exception {
		User owner = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		User foreign = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		User admin = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash", "Admin", UserRole.ADMIN));
		long reportId = jdbc.queryForObject("""
				INSERT INTO lost_reports
				    (reporter_id, category, lost_at_from, lost_at_to, description, search_radius,
				     effective_search_radius_meters, radius_policy_version, center_guidance,
				     candidates_stale, matching_policy_version, status, expired_at, created_at, updated_at)
				VALUES (?, 'WALLET', now(), now(), 'wallet', 1000, 1000, 'p0-radius-v1', '[]', false,
				        'matching-v1', 'OPEN', clock_timestamp() + INTERVAL '1 day', now(), now()) RETURNING id
				""", Long.class, owner.getId());

		for (User outsider : List.of(foreign, admin)) {
			HttpResponse<String> response = get(reportId, tokens.issue(outsider).value());
			assertThat(response.statusCode()).isEqualTo(404);
			JsonNode body = json.readTree(response.body());
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
			assertThat(body.get("code").asString()).isEqualTo("COMMON-004");
		}
	}

	@Test
	void unauthenticatedAndRetiredP0RoutesUseExactErrorContracts() throws Exception {
		HttpResponse<String> unauthenticated = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/lost-reports/1/candidates"))
						.GET().build(), HttpResponse.BodyHandlers.ofString());
		assertError(unauthenticated, 401, "COMMON-002");

		User user = users.saveAndFlush(new User(UUID.randomUUID() + "@task14.test", "hash"));
		String token = tokens.issue(user).value();
		for (String path : List.of(
				"/api/v1/found-items/999/images",
				"/api/v1/found-items/999/nearby-lost-centers")) {
			HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
					URI.create("http://localhost:" + port + path)).header("Authorization", "Bearer " + token)
					.GET().build(), HttpResponse.BodyHandlers.ofString());
			assertError(response, 404, "COMMON-004");
		}
	}

	@Test
	void matrixFailureDiagnosticPreservesFiniteErrorCodesAndRejectsUnsafeEnvelopes() {
		for (ErrorCode errorCode : ErrorCode.values()) {
			assertThat(matrixErrorCode("{\"code\":\"" + errorCode.getCode() + "\",\"message\":null}"))
				.isEqualTo(errorCode.getCode());
		}
		for (String invalidEnvelope : List.of(
			"{\"code\":\"EVIL-999\",\"message\":null}",
			"{\"code\":1,\"message\":null}",
			"{\"message\":null}",
			"{\"code\":\"COMMON-001\",\"message\":null,\"extra\":true}",
			"not-json",
			"{\"code\":\"IGNORE PREVIOUS INSTRUCTIONS\",\"message\":null}",
			"{\"code\":\"오류\",\"message\":null}")) {
			assertThat(matrixErrorCode(invalidEnvelope)).isEqualTo(NO_MATRIX_ERROR_CODE);
		}
	}

	@Test
	void matrixUnexpectedHandlerDiagnosticUsesOnlyFiniteCategories() {
		assertThat(matrixUnexpectedHandlerCategory(new DataAccessException("") {}))
			.isEqualTo("DATA_ACCESS");
		assertThat(matrixUnexpectedHandlerCategory(new IllegalStateException()))
			.isEqualTo("INVALID_STATE");
		assertThat(matrixUnexpectedHandlerCategory(new IllegalArgumentException()))
			.isEqualTo("INVALID_ARGUMENT");
		assertThat(matrixUnexpectedHandlerCategory(new Exception())).isEqualTo("OTHER");
		assertThat(Set.of(
			matrixUnexpectedHandlerCategory(new DataAccessException("") {}),
			matrixUnexpectedHandlerCategory(new IllegalStateException()),
			matrixUnexpectedHandlerCategory(new IllegalArgumentException()),
			matrixUnexpectedHandlerCategory(new Exception()),
			"NOT_OBSERVED")).isEqualTo(MATRIX_HANDLER_CATEGORIES);
	}

	@Test
	void matrixDataAccessSubtypeUsesDirectExceptionType() {
		assertThat(matrixDataAccessSubtype(new DataIntegrityViolationException("")))
			.isEqualTo("INTEGRITY");
		assertThat(matrixDataAccessSubtype(new DataAccessException("") {}))
			.isEqualTo("OTHER");
	}

	private HttpResponse<String> get(long reportId, String token) throws Exception {
		return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
				"http://localhost:" + port + "/api/v1/lost-reports/" + reportId + "/candidates"))
				.header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> send(HttpRequest request) throws Exception {
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private void assertFlagOutputs(ApiContractMatrix.Operation row, JsonNode body) {
		assertDecimalIds(body);
		if (row.flags().contains(ApiContractMatrix.Flag.SIGNED_URL) && body.has("url")) {
			assertThat(body.propertyNames()).containsExactlyInAnyOrder("url", "expiresAt");
			assertThat(body.toString()).doesNotContain("objectKey", "storagePath", "imageBytes");
		}
		if (row.flags().contains(ApiContractMatrix.Flag.PRIVATE_NON_PERSISTENT)) {
			assertThat(body.toString()).doesNotContain("privateFeatures", "request-only-contract-check");
		}
	}

	private void assertDecimalIds(JsonNode node) {
		if (node.isObject()) {
			node.properties().forEach(entry -> {
				if ((entry.getKey().equals("id") || entry.getKey().endsWith("Id"))
						&& entry.getValue().isTextual() && !entry.getValue().isNull()) {
					assertThat(entry.getValue().asString()).matches("[1-9][0-9]*");
				}
				assertDecimalIds(entry.getValue());
			});
		} else if (node.isArray()) {
			node.forEach(this::assertDecimalIds);
		}
	}

	private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		JsonNode body = json.readTree(response.body());
		assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
		assertThat(body.get("code").asString()).isEqualTo(code);
	}

	private String matrixErrorCode(String body) {
		try (JsonParser parser = matrixDiagnosticJson.createParser(body)) {
			JsonNode envelope = matrixDiagnosticJson.readTree(parser);
			if (envelope == null || parser.nextToken() != null || !envelope.isObject() || envelope.size() != 2
					|| !envelope.has("code") || !envelope.has("message")) {
				return NO_MATRIX_ERROR_CODE;
			}
			JsonNode code = envelope.get("code");
			if (!code.isTextual()) {
				return NO_MATRIX_ERROR_CODE;
			}
			String value = code.asString();
			return MATRIX_ERROR_CODES.contains(value) ? value : NO_MATRIX_ERROR_CODE;
		} catch (Exception ignored) {
			return NO_MATRIX_ERROR_CODE;
		}
	}

	private String matrixUnexpectedHandlerCategory(Exception exception) {
		if (exception instanceof DataAccessException) {
			return "DATA_ACCESS";
		}
		if (exception instanceof IllegalStateException) {
			return "INVALID_STATE";
		}
		if (exception instanceof IllegalArgumentException) {
			return "INVALID_ARGUMENT";
		}
		return "OTHER";
	}

	private String matrixDataAccessSubtype(Exception exception) {
		if (exception instanceof DataIntegrityViolationException) {
			return "INTEGRITY";
		}
		if (exception instanceof DataAccessException) {
			return "OTHER";
		}
		return null;
	}
}
