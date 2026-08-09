package kr.lostory.backend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendApplicationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void contextLoads() {
	}

	@Test
	void blankDatabaseHasPostgisAndFlywayVersionOne() {
		Boolean postgisInstalled = jdbcTemplate.queryForObject(
			"SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'postgis')",
			Boolean.class
		);
		String flywayVersion = jdbcTemplate.queryForObject(
			"SELECT version FROM flyway_schema_history WHERE success AND version = '1'",
			String.class
		);

		assertThat(postgisInstalled).isTrue();
		assertThat(flywayVersion).isEqualTo("1");
	}

	@Test
	void actuatorHealthIsPublic() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
			.GET()
			.build();
		HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

}
