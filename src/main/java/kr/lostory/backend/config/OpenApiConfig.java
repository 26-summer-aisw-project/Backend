package kr.lostory.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.math.BigDecimal;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

	@Bean
	OpenAPI lostoryOpenApi() {
		return new OpenAPI()
			.components(new Components()
				.addSecuritySchemes("bearerAuth", new SecurityScheme()
				.type(SecurityScheme.Type.HTTP)
				.scheme("bearer")
				.bearerFormat("JWT"))
				.addSchemas("ApiErrorResponse", new ObjectSchema()
					.addProperty("code", new StringSchema().description("기계 판독 오류 코드"))
					.addProperty("message", new StringSchema().description("사용자 안전 오류 메시지"))))
			.info(new Info()
				.title("Lostory REST API")
				.version("v1")
				.description("Lostory 백엔드 REST API 명세. docs/API_SPEC.md의 P0 21개와 P1 11개 계약을 제공합니다."));
	}

	@Bean
	OperationCustomizer contractMetadata() {
		Map<String, String> descriptions = Map.ofEntries(
			Map.entry("id", "대상의 10진 문자열 식별자"),
			Map.entry("foundItemId", "습득물의 10진 문자열 식별자"),
			Map.entry("centerId", "센터의 10진 문자열 식별자"),
			Map.entry("reportId", "분실 신고의 10진 문자열 식별자"),
			Map.entry("partnershipId", "파트너십의 10진 문자열 식별자"),
			Map.entry("handoverId", "인계의 10진 문자열 식별자"),
			Map.entry("activationToken", "별도 채널로 전달된 일회성 활성화 토큰"),
			Map.entry("latitude", "WGS84 위도"),
			Map.entry("longitude", "WGS84 경도"),
			Map.entry("page", "1부터 시작하는 페이지 번호, 기본값 1"),
			Map.entry("pageSize", "페이지당 항목 수, 기본값 20, 최대 100"),
			Map.entry("q", "센터 이름 또는 주소 검색어"),
			Map.entry("status", "허용된 상태 필터"),
			Map.entry("Idempotency-Key", "필수 소문자 표준 UUID(v1~v5), 기존 신고 열람은 추가 차감 없이 재생"));
		return (operation, handlerMethod) -> {
			if (handlerMethod.getMethod().getName().equals("confirmHandover")) {
				operation.setRequestBody(null);
			}
			if (operation.getParameters() != null) {
				operation.getParameters().forEach(parameter -> {
					parameter.setDescription(descriptions.getOrDefault(parameter.getName(), "요청 경계 매개변수"));
					if ("page".equals(parameter.getName())) {
						parameter.getSchema().setMinimum(BigDecimal.ONE);
					}
					if ("pageSize".equals(parameter.getName())) {
						parameter.getSchema().setMinimum(BigDecimal.ONE);
						parameter.getSchema().setMaximum(BigDecimal.valueOf(100));
					}
					if ("Idempotency-Key".equals(parameter.getName())) {
						parameter.getSchema().setFormat("uuid");
					}
				});
			}
			if (operation.getRequestBody() != null) {
				operation.getRequestBody().setRequired(true);
				operation.getRequestBody().setDescription("엔드포인트별 검증 규칙을 적용하는 필수 요청 본문");
				if (handlerMethod.getMethod().getName().equals("createDraft")
						|| handlerMethod.getMethod().getName().equals("replace")) {
					MediaType media = operation.getRequestBody().getContent().values().iterator().next();
					operation.getRequestBody().setContent(new Content().addMediaType("multipart/form-data", media));
					operation.getRequestBody().setDescription("image 이름의 사진 파일 정확히 한 장만 허용하는 multipart 요청");
				}
				if (handlerMethod.getMethod().getName().equals("close")) {
					operation.getRequestBody().setContent(new Content().addMediaType(
						"application/json", new MediaType().schema(new ObjectSchema().additionalProperties(false))));
					operation.getRequestBody().setDescription("추가 속성을 허용하지 않는 필수 빈 JSON 객체({})");
				}
			}
			operation.getResponses().forEach((status, response) -> {
				if (status.startsWith("2")) response.setDescription("계약에 맞는 성공 응답");
			});
			operation.getResponses().addApiResponse("400", error("요청 형식 또는 검증 오류"));
			operation.getResponses().addApiResponse("401", error("Bearer 인증 실패"));
			if (handlerMethod.getMethod().getName().equals("unlock")) {
				operation.getResponses().addApiResponse("409", error("멱등 키 충돌, 포인트 부족 또는 신고 상태 충돌"));
			}
			return operation;
		};
	}

	private ApiResponse error(String description) {
		return new ApiResponse().description(description).content(new Content().addMediaType(
			"application/json", new MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>()
				.$ref("#/components/schemas/ApiErrorResponse"))));
	}

}
