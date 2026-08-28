package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationIntegrationTest {

	@LocalServerPort private int port;
	@Autowired private ObjectMapper objectMapper;
	@Autowired
	@Qualifier("passwordByteLengthSchema")
	private GlobalOpenApiCustomizer passwordByteLengthSchema;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void generatedDocumentMatchesExecutableThirtyTwoOperationMatrix() throws Exception {
		JsonNode api = apiDocument();
		Set<String> actualOperations = operationInventory(api);
		Set<String> expectedOperations = ApiContractMatrix.OPERATIONS.stream()
			.map(ApiContractMatrix.Operation::key)
			.collect(java.util.stream.Collectors.toCollection(TreeSet::new));

		assertThat(ApiContractMatrix.OPERATIONS).hasSize(32);
		assertThat(ApiContractMatrix.OPERATIONS.stream().filter(row -> row.priority() == ApiContractMatrix.Priority.P0)).hasSize(21);
		assertThat(ApiContractMatrix.OPERATIONS.stream().filter(row -> row.priority() == ApiContractMatrix.Priority.P1)).hasSize(11);
		assertThat(expectedOperations).hasSize(32);
		assertThat(actualOperations).containsExactlyElementsOf(expectedOperations);
		assertThat(api.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asString())
			.isEqualTo("bearer");

		for (ApiContractMatrix.Operation row : ApiContractMatrix.OPERATIONS) {
			assertDecimalPathDeclaration(row);
			JsonNode operation = operation(api, row);
			assertThat(operation.path("summary").asString()).as(row.key() + " summary").containsPattern("[가-힣]");
			assertThat(operation.path("description").asString()).as(row.key() + " description").containsPattern("[가-힣]");
			assertSecurity(row, operation);
			assertParameters(row, operation);
			assertBody(api, row, operation);
			assertSuccessSchema(api, row, operation);
			assertErrorEnvelope(row, operation);
			assertFlagMetadata(api, row, operation);
		}
	}

	private void assertDecimalPathDeclaration(ApiContractMatrix.Operation row) {
		Set<String> expected = row.parameters().stream()
			.filter(name -> row.path().contains("{" + name + "}"))
			.filter(name -> name.equals("id") || name.endsWith("Id"))
			.collect(java.util.stream.Collectors.toCollection(TreeSet::new));
		assertThat(row.decimalPathParameters()).as(row.key() + " structural decimal declarations")
			.containsExactlyInAnyOrderElementsOf(expected);
	}

	@Test
	void generatedDocumentDeclaresOperationSpecificExecutableErrorStatuses() throws Exception {
		// Given
		JsonNode api = apiDocument();

		// When
		JsonNode adminCreate = api.path("paths").path("/api/v1/admin/lost-centers").path("post");
		JsonNode reportUpdate = api.path("paths").path("/api/v1/lost-reports/{reportId}").path("patch");
		JsonNode handoverAccept = api.path("paths")
			.path("/api/v1/dashboard/handovers/{handoverId}:accept").path("post");
		JsonNode imageRead = api.path("paths").path("/api/v1/found-items/{foundItemId}/image").path("get");
		JsonNode imageReplace = api.path("paths").path("/api/v1/found-items/{foundItemId}/image").path("put");

		// Then
		assertErrorStatus(adminCreate, "403");
		assertErrorStatus(reportUpdate, "409");
		assertErrorStatus(handoverAccept, "403");
		assertErrorStatus(handoverAccept, "409");
		assertErrorStatus(imageRead, "410");
		assertErrorStatus(imageReplace, "429");
		System.out.println("OPENAPI_ERROR_STATUS_OBSERVABLE protected=403 state=409 media=410 capacity=429");
	}

	@Test
	void generatedDocumentPinsExistingCommonAndConditionalErrorResponses() throws Exception {
		// Given
		JsonNode api = apiDocument();

		// When
		for (ApiContractMatrix.Operation row : ApiContractMatrix.OPERATIONS) {
			JsonNode operation = operation(api, row);

			// Then
			assertErrorStatus(operation, "400");
			assertErrorStatus(operation, "401");
		}
	}

	@Test
	void generatedDocumentDeclaresCommonNotFoundAndUnexpectedErrorResponses() throws Exception {
		// Given
		JsonNode api = apiDocument();

		// When
		// Then
		assertCommonErrorResponses(api);
	}

	@Test
	void generatedDocumentDeclaresUtf8BytePasswordContractsWithoutCharacterBounds() throws Exception {
		// Given
		JsonNode api = apiDocument();

		// Then
		assertUtf8BytePasswordContracts(api);
	}

	@Test
	void passwordByteCustomizerRetainsCharacterBoundsForWrongIntegralExtensionValues() {
		// Given
		List<Schema<?>> wrongIntegralContracts = List.of(
			passwordSchemaWithByteExtensions((byte) 7, 72L),
			passwordSchemaWithByteExtensions((short) 7, 72L),
			passwordSchemaWithByteExtensions(7, 72L),
			passwordSchemaWithByteExtensions((byte) 8, 71L));
		ObjectSchema container = new ObjectSchema();
		for (int index = 0; index < wrongIntegralContracts.size(); index++) {
			container.addProperty("wrongIntegral" + index, wrongIntegralContracts.get(index));
		}
		OpenAPI api = new OpenAPI().components(new Components().addSchemas("PasswordPredicateRegression", container));

		// When
		passwordByteLengthSchema.customise(api);

		// Then
		wrongIntegralContracts.forEach(property -> {
			assertThat(property.getMinLength()).isEqualTo(1);
			assertThat(property.getMaxLength()).isEqualTo(72);
		});
	}

	@Test
	void literalCurlGeneratedDocumentAdvertisesCommonErrorResponsesForEveryOperation() throws Exception {
		// Given
		JsonNode api = curlApiDocument();

		// When
		assertCommonErrorResponses(api);
		assertUtf8BytePasswordContracts(api);

		// Then
		System.out.println("CURL_OPENAPI_COMMON_ERROR_OBSERVABLE status=200 operations=32 responses_404=32 "
			+ "responses_500=32 media=application/json ref=ApiErrorResponse fields=code,message");
		System.out.println("CURL_OPENAPI_PASSWORD_OBSERVABLE status=200 password_schemas=3 format_password=3 "
			+ "byte_contract=3 character_bounds_absent=3");
	}

	private void assertCommonErrorResponses(JsonNode api) {
		JsonNode errorSchema = api.path("components").path("schemas").path("ApiErrorResponse");
		assertThat(errorSchema.path("properties").propertyNames())
			.containsExactlyInAnyOrder("code", "message");
		int documented404 = 0;
		int documented500 = 0;
		for (ApiContractMatrix.Operation row : ApiContractMatrix.OPERATIONS) {
			JsonNode operation = operation(api, row);
			assertErrorStatus(operation, "404");
			assertErrorStatus(operation, "500");
			documented404++;
			documented500++;
		}
		assertThat(documented404).isEqualTo(32);
		assertThat(documented500).isEqualTo(32);
	}

	private JsonNode requestPasswordSchema(JsonNode api, String path) {
		JsonNode operation = api.path("paths").path(path).path("post");
		JsonNode request = operation.path("requestBody");
		return resolve(api, firstSchema(request.path("content"))).path("properties").path("password");
	}

	private void assertUtf8BytePasswordContracts(JsonNode api) {
		assertUtf8BytePasswordSchema(requestPasswordSchema(api, "/api/v1/auth/signup"), "signup");
		assertUtf8BytePasswordSchema(requestPasswordSchema(api, "/api/v1/auth/login"), "login");
		assertUtf8BytePasswordSchema(
			requestPasswordSchema(api, "/api/v1/partner-manager-activations/{activationToken}"),
			"partner-manager activation");
		int byteContracts = 0;
		for (var component : api.path("components").path("schemas").properties()) {
			for (JsonNode property : component.getValue().path("properties")) {
				if (property.path("x-password-byte-minimum").asInt() == 8
					&& property.path("x-password-byte-maximum").asInt() == 72
					&& property.path("x-password-byte-encoding").asString().equals("UTF-8")) {
					byteContracts++;
				}
			}
		}
		assertThat(byteContracts).as("password byte-contract schemas").isEqualTo(3);
	}

	private void assertUtf8BytePasswordSchema(JsonNode password, String payload) {
		assertThat(password.path("type").asString()).as(payload + " password type").isEqualTo("string");
		assertThat(password.path("format").asString()).as(payload + " password format").isEqualTo("password");
		assertThat(password.path("x-password-byte-minimum").asInt()).as(payload + " password byte minimum").isEqualTo(8);
		assertThat(password.path("x-password-byte-maximum").asInt()).as(payload + " password byte maximum").isEqualTo(72);
		assertThat(password.path("x-password-byte-encoding").asString())
			.as(payload + " password byte encoding").isEqualTo("UTF-8");
		assertThat(password.has("minLength")).as(payload + " password character minimum absent").isFalse();
		assertThat(password.has("maxLength")).as(payload + " password character maximum absent").isFalse();
	}

	private Schema<?> passwordSchemaWithByteExtensions(Object minimum, Object maximum) {
		Schema<?> property = new StringSchema();
		property.setMinLength(1);
		property.setMaxLength(72);
		property.addExtension("x-password-byte-minimum", minimum);
		property.addExtension("x-password-byte-maximum", maximum);
		property.addExtension("x-password-byte-encoding", "UTF-8");
		return property;
	}

	@Test
	void retiredFoundItemRoutesReturnNotFoundAndStayOutOfOpenApi() throws Exception {
		JsonNode api = apiDocument();
		int registration = status(jsonRequest("POST", "/api/v1/found-items", "{}"));
		int imageList = status(request("GET", "/api/v1/found-items/1/images"));
		int imageUpload = status(request("POST", "/api/v1/found-items/1/images"));
		int nearbyAlias = status(request("GET", "/api/v1/nearby-lost-centers"));

		assertThat(List.of(registration, imageList, imageUpload)).containsOnly(404);
		assertThat(nearbyAlias).isEqualTo(401);
		assertThat(api.path("paths").has("/api/v1/found-items/{id}/images")).isFalse();
		assertThat(api.path("paths").has("/api/v1/nearby-lost-centers")).isFalse();
	}

	private JsonNode apiDocument() throws Exception {
		HttpResponse<String> response = httpClient.send(request("GET", "/v3/api-docs"),
			HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		return objectMapper.readTree(response.body());
	}

	private JsonNode curlApiDocument() throws Exception {
		Process process = new ProcessBuilder("curl", "-i", "--max-time", "15",
			"http://127.0.0.1:" + port + "/v3/api-docs").start();
		try {
			String raw = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			process.getErrorStream().readAllBytes();
			assertThat(process.waitFor(15, TimeUnit.SECONDS)).as("OpenAPI curl timeout").isTrue();
			assertThat(process.exitValue()).as("OpenAPI curl exit").isZero();
			int split = raw.lastIndexOf("\r\n\r\n");
			assertThat(split).as("OpenAPI curl response split").isPositive();
			String statusLine = raw.substring(0, raw.indexOf("\r\n"));
			assertThat(Integer.parseInt(statusLine.split(" ")[1])).isEqualTo(200);
			return objectMapper.readTree(raw.substring(split + 4));
		} finally {
			if (process.isAlive()) process.destroyForcibly();
		}
	}

	private void assertErrorStatus(JsonNode operation, String status) {
		JsonNode response = operation.path("responses").path(status);
		assertThat(response.isMissingNode()).as("documented status " + status).isFalse();
		assertThat(response.path("content").propertyNames()).as("documented status " + status + " media")
			.containsExactly("application/json");
		assertThat(response.path("content").path("application/json").path("schema").path("$ref").asString())
			.isEqualTo("#/components/schemas/ApiErrorResponse");
	}

	private void assertSecurity(ApiContractMatrix.Operation row, JsonNode operation) {
		if (row.security() == ApiContractMatrix.Security.PUBLIC) {
			assertThat(operation.get("security")).as(row.key() + " public security").isNull();
			return;
		}
		assertThat(operation.path("security").get(0).has("bearerAuth"))
			.as(row.key() + " bearer security").isTrue();
	}

	private void assertParameters(ApiContractMatrix.Operation row, JsonNode operation) {
		Set<String> documented = new TreeSet<>();
		operation.path("parameters").forEach(parameter -> {
			documented.add(parameter.path("name").asString());
			assertThat(parameter.path("description").asString())
				.as(row.key() + " parameter " + parameter.path("name").asString()).containsPattern("[가-힣]");
		});
		Set<String> expected = new TreeSet<>(row.parameters());
		expected.remove("image");
		assertThat(documented).as(row.key() + " parameters")
			.containsExactlyInAnyOrderElementsOf(expected);
	}

	private void assertBody(JsonNode api, ApiContractMatrix.Operation row, JsonNode operation) {
		JsonNode requestBody = operation.get("requestBody");
		if (row.body() == ApiContractMatrix.Body.NONE) {
			assertThat(requestBody).as(row.key() + " request body").isNull();
			return;
		}
		assertThat(requestBody).as(row.key() + " request body").isNotNull();
		assertThat(requestBody.path("description").asString()).as(row.key() + " body description")
			.containsPattern("[가-힣]");
		assertThat(requestBody.path("required").asBoolean()).as(row.key() + " required body").isTrue();
		Set<String> mediaTypes = new TreeSet<>();
		mediaTypes.addAll(requestBody.path("content").propertyNames());
		if (row.body() == ApiContractMatrix.Body.MULTIPART) {
			assertThat(mediaTypes).as(row.key() + " multipart media")
				.containsExactly("multipart/form-data");
		} else {
			assertThat(mediaTypes).as(row.key() + " json media")
				.containsExactly("application/json");
		}
		JsonNode schema = resolve(api, firstSchema(requestBody.path("content")));
		assertThat(schema.path("properties").propertyNames()).as(row.key() + " body properties")
			.containsExactlyInAnyOrderElementsOf(row.bodyProperties());
		Set<String> required = new TreeSet<>();
		schema.path("required").forEach(name -> required.add(name.asString()));
		assertThat(required).as(row.key() + " required body properties")
			.containsExactlyInAnyOrderElementsOf(row.requiredBodyProperties());
	}

	private void assertSuccessSchema(JsonNode api, ApiContractMatrix.Operation row, JsonNode operation) {
		JsonNode response = operation.path("responses").path(Integer.toString(row.successStatus()));
		assertThat(response.isMissingNode()).as(row.key() + " success status").isFalse();
		assertThat(response.path("description").asString()).as(row.key() + " success description")
			.containsPattern("[가-힣]");
		JsonNode resolved = resolve(api, firstSchema(response.path("content")));
		assertThat(resolved.path("properties").propertyNames()).as(row.key() + " response " + response)
			.containsExactlyInAnyOrderElementsOf(row.successFields());
	}

	private void assertErrorEnvelope(ApiContractMatrix.Operation row, JsonNode operation) {
		String status = row.security() == ApiContractMatrix.Security.PUBLIC ? "400" : "401";
		JsonNode error = operation.path("responses").path(status);
		assertThat(error.path("content").path("application/json").path("schema").path("$ref").asString())
			.as(row.key() + " error envelope").endsWith("/ApiErrorResponse");
	}

	private void assertFlagMetadata(JsonNode api, ApiContractMatrix.Operation row, JsonNode operation) {
		if (row.flags().contains(ApiContractMatrix.Flag.PAGE)) {
			for (String name : Set.of("page", "pageSize")) {
				JsonNode parameter = parameter(operation, name);
				assertThat(parameter.path("schema").path("default").asInt()).as(row.key() + " " + name + " default")
					.isEqualTo(name.equals("page") ? 1 : 20);
			}
			assertThat(parameter(operation, "page").path("schema").path("minimum").asInt()).isEqualTo(1);
			assertThat(parameter(operation, "pageSize").path("schema").path("minimum").asInt()).isEqualTo(1);
			assertThat(parameter(operation, "pageSize").path("schema").path("maximum").asInt()).isEqualTo(100);
		}
		for (String name : row.decimalPathParameters()) {
			assertThat(row.path()).as(row.key() + " decimal template").contains("{" + name + "}");
			assertThat(row.parameters()).as(row.key() + " decimal matrix parameter").contains(name);
			JsonNode decimal = parameter(operation, name);
			assertThat(decimal.path("in").asString()).as(row.key() + " " + name + " location")
				.isEqualTo("path");
			assertThat(decimal.path("required").asBoolean()).as(row.key() + " " + name + " required")
				.isTrue();
			assertThat(decimal.path("description").asString()).as(row.key() + " " + name + " description")
				.contains("10진");
			assertThat(decimal.path("schema").path("type").asString())
				.as(row.key() + " " + name + " type").isEqualTo("integer");
			assertThat(decimal.path("schema").path("format").asString())
				.as(row.key() + " " + name + " format").isEqualTo("int64");
		}
		if (row.flags().contains(ApiContractMatrix.Flag.IDEMPOTENCY)) {
			JsonNode key = parameter(operation, "Idempotency-Key");
			assertThat(key.path("required").asBoolean()).isTrue();
			assertThat(key.path("schema").path("format").asString()).isEqualTo("uuid");
		}
		if (row.flags().contains(ApiContractMatrix.Flag.REPLAY)) {
			assertThat(operation.path("responses").has("409")).isTrue();
		}
		if (row.flags().contains(ApiContractMatrix.Flag.SIGNED_URL)) {
			assertThat(operation.path("description").asString()).contains("서명 URL");
		}
		if (row.flags().contains(ApiContractMatrix.Flag.PRIVATE_NON_PERSISTENT)) {
			assertThat(operation.path("description").asString()).contains("저장");
		}
		if (row.flags().contains(ApiContractMatrix.Flag.SAFE_RETURN)) {
			JsonNode schema = resolve(api, firstSchema(operation.path("responses").path("201").path("content")));
			assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrder(
				"returnId", "itemId", "reportId", "status", "rewardGranted");
		}
	}

	private JsonNode resolve(JsonNode api, JsonNode schema) {
		String reference = schema.path("$ref").asString();
		if (reference.isEmpty()) return schema;
		return api.path("components").path("schemas").path(reference.substring(reference.lastIndexOf('/') + 1));
	}

	private JsonNode firstSchema(JsonNode content) {
		return content.properties().stream().findFirst().map(entry -> entry.getValue().path("schema"))
			.orElseGet(objectMapper::createObjectNode);
	}

	private JsonNode parameter(JsonNode operation, String name) {
		for (JsonNode parameter : operation.path("parameters")) {
			if (name.equals(parameter.path("name").asString())) return parameter;
		}
		return objectMapper.createObjectNode();
	}

	private JsonNode operation(JsonNode api, ApiContractMatrix.Operation row) {
		return api.path("paths").path(row.path()).path(row.method().toLowerCase());
	}

	private int status(HttpRequest request) throws Exception {
		return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private HttpRequest request(String method, String path) {
		return HttpRequest.newBuilder(uri(path)).method(method, HttpRequest.BodyPublishers.noBody()).build();
	}

	private HttpRequest jsonRequest(String method, String path, String body) {
		return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
			.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private static Set<String> operationInventory(JsonNode api) {
		Set<String> operations = new TreeSet<>();
		api.path("paths").properties().forEach(path -> path.getValue().properties().forEach(method ->
			operations.add(method.getKey().toUpperCase() + " " + path.getKey())));
		return operations;
	}
}
