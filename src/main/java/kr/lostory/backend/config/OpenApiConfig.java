package kr.lostory.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI lostoryOpenApi() {
		return new OpenAPI()
			.components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT")))
			.info(new Info()
				.title("Lostory REST API")
				.version("v1")
				.description("Lostory 백엔드 REST API 명세. docs/API_SPEC.md의 현재 P0 계약만 제공합니다."));
	}

}
