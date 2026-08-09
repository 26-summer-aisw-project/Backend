package kr.lostory.backend;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestContainerConfig {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		DockerImageName image = DockerImageName.parse("postgis/postgis:16-3.5-alpine")
			.asCompatibleSubstituteFor("postgres");
		return new PostgreSQLContainer(image);
	}

}
