package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;
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

	private static final Set<String> P0_OPERATIONS = Set.of(
		"GET /api/v1/found-items",
		"GET /api/v1/found-items/{foundItemId}/image",
		"GET /api/v1/found-items/{foundItemId}/nearby-centers",
		"GET /api/v1/found-items/{id}",
		"GET /api/v1/lost-centers",
		"GET /api/v1/lost-centers/nearby",
		"GET /api/v1/lost-reports",
		"GET /api/v1/lost-reports/{reportId}",
		"GET /api/v1/lost-reports/{reportId}/candidates",
		"GET /api/v1/users/me",
		"PATCH /api/v1/admin/lost-centers/{centerId}",
		"PATCH /api/v1/found-items/{id}/registration",
		"PATCH /api/v1/lost-reports/{reportId}",
		"POST /api/v1/admin/lost-centers",
		"POST /api/v1/auth/login",
		"POST /api/v1/auth/signup",
		"POST /api/v1/found-items/drafts",
		"POST /api/v1/found-items/{id}:confirm-handover",
		"POST /api/v1/lost-reports",
		"POST /api/v1/lost-reports/{reportId}:close",
		"PUT /api/v1/found-items/{foundItemId}/image"
	);

	private static final Set<String> PUBLIC_OPERATIONS = Set.of(
		"POST /api/v1/auth/login",
		"POST /api/v1/auth/signup"
	);

	@LocalServerPort
	private int port;

	@Autowired
	private ObjectMapper objectMapper;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@Test
	void documentsExactP0ContractInKoreanWithExpectedSecurity() throws Exception {
		// Given
		HttpRequest request = HttpRequest.newBuilder(uri("/v3/api-docs")).GET().build();

		// When
		HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
		JsonNode api = objectMapper.readTree(response.body());

		// Then
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(operationInventory(api)).containsExactlyElementsOf(new TreeSet<>(P0_OPERATIONS));
		assertThat(api.path("components").path("securitySchemes").path("bearerAuth").path("scheme").asString())
			.isEqualTo("bearer");
		for (String operationKey : P0_OPERATIONS) {
			String[] parts = operationKey.split(" ", 2);
			JsonNode operation = api.path("paths").path(parts[1]).path(parts[0].toLowerCase());
			assertThat(operation.path("summary").asString()).as(operationKey + " summary").containsPattern("[가-힣]");
			assertThat(operation.path("description").asString()).as(operationKey + " description").containsPattern("[가-힣]");
			if (PUBLIC_OPERATIONS.contains(operationKey)) {
				assertThat(operation.get("security")).as(operationKey + " public security").isNull();
			} else {
				assertThat(operation.path("security").get(0).has("bearerAuth"))
					.as(operationKey + " bearer security")
					.isTrue();
			}
		}
		assertThat(api.path("components").path("schemas").path("AuthRequest").path("properties").path("password")
			.path("format").asString()).isEqualTo("password");
		assertThat(api.path("components").path("schemas").path("SignupRequest").path("properties").path("displayName")
			.path("description").asString()).containsPattern("[가-힣]");
	}

	@Test
	void retiredFoundItemRoutesReturnNotFound() throws Exception {
		// Given
		HttpRequest emptyJsonRegistration = HttpRequest.newBuilder(uri("/api/v1/found-items"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("{}"))
			.build();
		HttpRequest imageList = HttpRequest.newBuilder(uri("/api/v1/found-items/1/images")).GET().build();
		HttpRequest imageUpload = HttpRequest.newBuilder(uri("/api/v1/found-items/1/images"))
			.POST(HttpRequest.BodyPublishers.noBody())
			.build();

		// When
		int registrationStatus = status(emptyJsonRegistration);
		int imageListStatus = status(imageList);
		int imageUploadStatus = status(imageUpload);

		// Then
		assertThat(registrationStatus).isEqualTo(404);
		assertThat(imageListStatus).isEqualTo(404);
		assertThat(imageUploadStatus).isEqualTo(404);
	}

	private int status(HttpRequest request) throws Exception {
		return httpClient.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private static Set<String> operationInventory(JsonNode api) {
		Set<String> operations = new TreeSet<>();
		api.path("paths").properties().forEach(path ->
			path.getValue().properties().forEach(method ->
				operations.add(method.getKey().toUpperCase() + " " + path.getKey())));
		return operations;
	}
}
