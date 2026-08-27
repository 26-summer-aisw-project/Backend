package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationIntegrationTest {

	@LocalServerPort private int port;
	@Autowired private ObjectMapper objectMapper;
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
