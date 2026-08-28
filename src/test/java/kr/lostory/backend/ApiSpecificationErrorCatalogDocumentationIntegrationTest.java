package kr.lostory.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import kr.lostory.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSpecificationErrorCatalogDocumentationIntegrationTest {

    private static final Path API_SPECIFICATION = Path.of("docs", "API_SPEC.md");
    @LocalServerPort
    private int port;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void errorCatalogSeparatesInvalidBearerFromConcealedActivationCapability() throws IOException {
        // Given
        JsonNode catalog = errorCatalog();

        // When
        JsonNode invalidBearer = error(catalog, ErrorCode.INVALID_TOKEN.getCode());
        JsonNode concealedActivationCapability = error(catalog, ErrorCode.RESOURCE_NOT_FOUND.getCode());

        // Then
        assertThat(invalidBearer.get("httpStatus").asInt()).isEqualTo(401);
        assertThat(invalidBearer.get("meaning").asString()).isEqualTo("invalid-access-token");
        assertThat(concealedActivationCapability.get("httpStatus").asInt()).isEqualTo(404);
        assertThat(concealedActivationCapability.get("meaning").asString())
                .isEqualTo("missing-or-concealed-resource");
    }

    @Test
    void literalCurlsDistinguishInvalidBearerInputValidationAndConcealedCapability() throws Exception {
        // Given
        String baseUrl = "http://127.0.0.1:" + port;

        // When
        CurlResult bearer = curl(List.of(
                "curl", "-i", "--max-time", "15", "-H", "Authorization: Bearer malformed",
                baseUrl + "/api/v1/users/me"));
        CurlResult bodylessActivation = curl(List.of(
                "curl", "-i", "--max-time", "15", "-X", "POST",
                baseUrl + "/api/v1/partner-manager-activations/malformed-capability"));
        CurlResult activation = curl(List.of(
                "curl", "-i", "--max-time", "15", "-X", "POST", "-H", "Content-Type: application/json",
                "--data", "{\"password\":\"safe-password-123\"}",
                baseUrl + "/api/v1/partner-manager-activations/malformed-capability"));

        // Then
        assertError(bearer, 401, ErrorCode.INVALID_TOKEN.getCode());
        assertError(bodylessActivation, 400, ErrorCode.INVALID_REQUEST.getCode());
        assertError(activation, 404, ErrorCode.RESOURCE_NOT_FOUND.getCode());
        System.out.printf("R2F_MANUAL_QA bearer_status=%d bearer_code=%s bodyless_activation_status=%d "
                        + "bodyless_activation_code=%s activation_status=%d activation_code=%s%n",
                bearer.status(), bearer.body().get("code").asString(),
                bodylessActivation.status(), bodylessActivation.body().get("code").asString(),
                activation.status(), activation.body().get("code").asString());
    }

    private JsonNode error(JsonNode catalog, String code) {
        for (JsonNode entry : catalog) {
            JsonNode documentedCode = entry.get("code");
            if (documentedCode != null && code.equals(documentedCode.asString())) {
                return entry;
            }
        }
        throw new AssertionError("Missing documented error code: " + code);
    }

    private JsonNode errorCatalog() throws IOException {
        String specification = Files.readString(API_SPECIFICATION);
        int section = specification.indexOf("### 1.2 공통 오류");
        int jsonStart = specification.indexOf("```json\n[", section);
        int jsonEnd = specification.indexOf("```", jsonStart + "```json".length());
        return json.readTree(specification.substring(jsonStart + "```json".length(), jsonEnd));
    }

    private CurlResult curl(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String response;
        try {
            response = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("curl exceeded cleanup deadline");
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
        assertThat(process.exitValue()).isZero();
        int bodyStart = response.lastIndexOf("\r\n\r\n");
        assertThat(bodyStart).isGreaterThan(0);
        int status = Integer.parseInt(response.lines().findFirst().orElseThrow().trim().split("\\s+")[1]);
        return new CurlResult(status, json.readTree(response.substring(bodyStart + 4)));
    }

    private void assertError(CurlResult result, int status, String code) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.body().propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(result.body().get("code").asString()).isEqualTo(code);
    }

    private record CurlResult(int status, JsonNode body) {
    }
}
