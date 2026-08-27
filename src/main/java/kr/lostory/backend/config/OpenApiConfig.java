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
import java.util.Set;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
	private static final Set<String> PUBLIC_OPERATIONS = Set.of(
		"AuthController#signup",
		"AuthController#login",
		"PartnerCenterController#activate"
	);
	private static final Set<String> STATE_CONFLICT_OPERATIONS = Set.of(
		"AuthController#signup",
		"FoundItemController#finalizeRegistration",
		"FoundItemController#confirmHandover",
		"LostReportController#candidates",
		"LostReportController#update",
		"LostReportController#close",
		"PartnerCenterController#approve",
		"PartnerCenterController#activate",
		"DashboardHandoverController#list",
		"DashboardHandoverController#accept",
		"DashboardHandoverController#reject",
		"ReturnController#record",
		"CandidateAccessController#unlock"
	);
	private static final Set<String> TERMINAL_MEDIA_OPERATIONS = Set.of(
		"FoundItemImageController#get"
	);
	private static final Set<String> VISION_CAPACITY_OPERATIONS = Set.of(
		"FoundItemController#createDraft",
		"FoundItemImageController#replace"
	);

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
			String operationKey = handlerMethod.getBeanType().getSimpleName() + "#"
				+ handlerMethod.getMethod().getName();
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
			if (!PUBLIC_OPERATIONS.contains(operationKey)) {
				operation.getResponses().addApiResponse("403", error("역할 또는 리소스 권한 거부"));
			}
			if (STATE_CONFLICT_OPERATIONS.contains(operationKey)) {
				operation.getResponses().addApiResponse("409", error("현재 상태에서 실행할 수 없는 요청"));
			}
			if (TERMINAL_MEDIA_OPERATIONS.contains(operationKey)) {
				operation.getResponses().addApiResponse("410", error("미디어를 더 이상 사용할 수 없음"));
			}
			if (VISION_CAPACITY_OPERATIONS.contains(operationKey)) {
				operation.getResponses().addApiResponse("429", error("비전 처리 용량 초과"));
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
