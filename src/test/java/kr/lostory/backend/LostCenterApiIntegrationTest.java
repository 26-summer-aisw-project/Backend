package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import kr.lostory.backend.auth.JwtTokenService;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.StorageMethod;
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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "center.nearby-radius=1001"
)
class LostCenterApiIntegrationTest {

    private static final BigDecimal LATITUDE = new BigDecimal("35.0000000");
    private static final BigDecimal LONGITUDE = new BigDecimal("128.0000000");

    @LocalServerPort int port;
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired FoundItemRepository items;
    @Autowired JdbcTemplate jdbc;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void cleanFixtures() {
        jdbc.update("DELETE FROM found_items WHERE name LIKE 'task7-%'");
        jdbc.update("DELETE FROM lost_centers WHERE source_key LIKE 'test:%'");
    }

    @Test
    void publicDirectoryAndNearbyQueriesUseActiveVerifiedOneKilometerPolicy() throws Exception {
        User user = user(UserRole.USER);
        String token = tokens.issue(user).value();
        long firstTieId = center("test:first-tie", 100, "official_verified", true);
        long secondTieId = center("test:second-tie", 100, "admin_verified", true);
        for (int distance = 200; distance <= 1000; distance += 100) {
            center("test:distance-" + distance, distance, "official_board_verified", true);
        }
        center("test:outside", 1000.5, "official_verified", true);
        center("test:inactive", 50, "official_verified", false);
        center("test:unverified", 50, "inactive", true);

        HttpResponse<String> nearby = get("/api/v1/lost-centers/nearby?latitude=35&longitude=128", token);
        JsonNode nearbyJson = json.readTree(nearby.body());

        assertThat(nearby.statusCode()).isEqualTo(200);
        assertThat(nearbyJson.get("data")).hasSize(10);
        assertThat(nearbyJson.get("data").get(0).get("id").asString()).isEqualTo(Long.toString(firstTieId));
        assertThat(nearbyJson.get("data").get(1).get("id").asString()).isEqualTo(Long.toString(secondTieId));
        assertThat(nearby.body()).doesNotContain("outside", "inactive", "unverified", "distance-1000");
        jdbc.update("UPDATE lost_centers SET is_active = false WHERE source_key = 'test:first-tie'");
        assertThat(get("/api/v1/lost-centers/nearby?latitude=35&longitude=128", token).body())
                .contains("distance-1000").doesNotContain("outside");
        jdbc.update("UPDATE lost_centers SET is_active = false WHERE source_key = 'test:second-tie'");
        JsonNode strictBoundary = json.readTree(
                get("/api/v1/lost-centers/nearby?latitude=35&longitude=128", token).body());
        assertThat(strictBoundary.get("data")).hasSize(9);
        assertThat(strictBoundary.toString()).doesNotContain("outside");

        HttpResponse<String> directory = get("/api/v1/lost-centers?page=1&pageSize=5&q=distance", token);
        JsonNode directoryJson = json.readTree(directory.body());
        assertThat(directory.statusCode()).isEqualTo(200);
        assertThat(directoryJson.get("data")).hasSize(5);
        assertThat(directoryJson.get("meta").get("totalItems").asInt()).isEqualTo(9);
        assertThat(directoryJson.get("data").get(0).propertyNames()).containsExactlyInAnyOrder(
                "id", "name", "address", "contactPhone", "location", "isActive");
        assertThat(get("/api/v1/lost-centers", token).statusCode()).isEqualTo(200);
        System.out.println("P0_CENTER_HTTP_POLICY_OBSERVABLE configuredRadius=1001 enforcedRadius=1000 "
                + "within1000=true outside1001=false max10=true order=distance,id");
    }

    @Test
    void ownerRouteAndAdminCrudEnforceAuthorizationAndSourceImmutability() throws Exception {
        User owner = user(UserRole.USER);
        User foreign = user(UserRole.USER);
        User admin = user(UserRole.ADMIN);
        FoundItem item = items.saveAndFlush(new FoundItem(owner.getId(), "task7-wallet", "WALLET",
                "wallet", Instant.now(), LATITUDE, LONGITUDE, "address", null,
                StorageMethod.LEFT_IN_PLACE, null, null));
        center("test:owner-nearby", 250, "official_verified", true);

        HttpResponse<String> ownerResponse = get(
                "/api/v1/found-items/" + item.getId() + "/nearby-centers", tokens.issue(owner).value());
        assertThat(ownerResponse.statusCode()).isEqualTo(200);
        assertThat(ownerResponse.body()).contains("owner-nearby");
        assertError(get("/api/v1/found-items/" + item.getId() + "/nearby-centers",
                tokens.issue(foreign).value()), 404, "COMMON-004");
        assertThat(get("/api/v1/found-items/" + item.getId() + "/nearby-lost-centers",
                tokens.issue(owner).value()).statusCode()).isEqualTo(404);

        String createBody = """
                {"name":"관리자 센터","address":"부산시 테스트로 1","contactPhone":"051-000-0000",
                 "location":{"latitude":35.0,"longitude":128.0}}
                """;
        HttpResponse<String> created = request("POST", "/api/v1/admin/lost-centers",
                tokens.issue(admin).value(), createBody);
        assertThat(created.statusCode()).isEqualTo(201);
        String centerId = json.readTree(created.body()).get("id").asString();
        assertThat(jdbc.queryForObject("SELECT verification_status FROM lost_centers WHERE id = ?",
                String.class, Long.valueOf(centerId))).isEqualTo("admin_verified");
        assertThat(get("/api/v1/lost-centers?q=관리자", tokens.issue(owner).value()).body())
                .contains("관리자 센터");
        assertThat(get("/api/v1/lost-centers/nearby?latitude=35&longitude=128",
                tokens.issue(owner).value()).body()).contains("관리자 센터");

        HttpResponse<String> patched = request("PATCH", "/api/v1/admin/lost-centers/" + centerId,
                tokens.issue(admin).value(), "{\"contactPhone\":\"051-111-1111\",\"isActive\":false}");
        assertThat(patched.statusCode()).isEqualTo(200);
        assertThat(patched.body()).contains("051-111-1111", "\"isActive\":false");
        assertThat(get("/api/v1/lost-centers/nearby?latitude=35&longitude=128",
                tokens.issue(owner).value()).body()).doesNotContain("관리자 센터");
        assertError(request("POST", "/api/v1/admin/lost-centers", tokens.issue(owner).value(), createBody),
                403, "COMMON-003");
        assertError(request("PATCH", "/api/v1/admin/lost-centers/" + centerId,
                tokens.issue(owner).value(), "{\"isActive\":true}"), 403, "COMMON-003");
        Long csvId = jdbc.queryForObject(
                "SELECT id FROM lost_centers WHERE is_csv_managed ORDER BY id LIMIT 1", Long.class);
        assertError(request("PATCH", "/api/v1/admin/lost-centers/" + csvId,
                tokens.issue(admin).value(), "{\"isActive\":false}"), 400, "COMMON-001");
        assertError(request("POST", "/api/v1/admin/lost-centers", tokens.issue(admin).value(),
                "{\"name\":\"bad\",\"address\":\"a\",\"contactPhone\":\"p\","
                        + "\"location\":{\"latitude\":91,\"longitude\":128}}"), 400, "COMMON-001");
        assertError(get("/api/v1/lost-centers/nearby?latitude=35", tokens.issue(owner).value()),
                400, "COMMON-001");
        System.out.println("P0_CENTER_HTTP_ADMIN_OBSERVABLE owner=200 foreign=404 admin-create=201 "
                + "admin-patch=200 csv-patch=400");
    }

    private User user(UserRole role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash", "User", role));
    }

    private long center(String key, double meters, String verification, boolean active) {
        return jdbc.queryForObject("""
                INSERT INTO lost_centers (source_key, name, address, location, contact_phone, operating_hours,
                    verification_status, is_active, is_csv_managed, created_at, updated_at)
                VALUES (?, ?, 'test address', ST_Project(ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?, radians(90)), '02-000-0000', 'always', ?, ?, false, now(), now()) RETURNING id
                """, Long.class, key, key, LONGITUDE, LATITUDE, meters, verification, active);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String path, String token, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path.replace(" ", "%20"));
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("code", "message");
        assertThat(body.get("code").asString()).isEqualTo(code);
    }
}
