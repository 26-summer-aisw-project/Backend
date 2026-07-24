package kr.lostory.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI lostoryOpenApi() {
		return new OpenAPI()
			.info(new Info()
				.title("Lostory REST API")
				.version("v1")
				.description("Lostory 백엔드 REST API 명세"));
	}

}
