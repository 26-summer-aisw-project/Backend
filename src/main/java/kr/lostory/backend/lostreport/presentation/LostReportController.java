package kr.lostory.backend.lostreport.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.lostreport.application.LostReportApiService;
import kr.lostory.backend.lostreport.application.LostReportCandidateService;
import kr.lostory.backend.lostreport.domain.LostReportStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/lost-reports")
@Tag(name = "분실 신고", description = "분실 신고와 점수 후보 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class LostReportController {

	private final LostReportApiService service;
	private final LostReportCandidateService candidateService;

	public LostReportController(LostReportApiService service, LostReportCandidateService candidateService) {
		this.service = service;
		this.candidateService = candidateService;
	}

	@GetMapping("/{reportId}/candidates")
	@Operation(summary = "점수 후보 조회", description = "열린 분실 신고의 매칭 후보를 점수와 순위만 포함해 조회합니다.")
	public LostReportCandidateResponse candidates(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt
	) {
		return candidateService.candidates(reportId, Long.valueOf(jwt.getSubject()));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "분실 신고 생성", description = "분실 정보와 이동 경로를 저장하고 센터 안내와 최초 점수 후보를 생성합니다.")
	public LostReportResponses.Create create(
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateLostReportRequest request
	) {
		return service.create(Long.valueOf(jwt.getSubject()), request);
	}

	@GetMapping
	@Operation(summary = "내 분실 신고 목록 조회", description = "현재 사용자의 분실 신고를 상태 필터와 페이지 조건으로 조회합니다.")
	public LostReportResponses.ListResult list(
			@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize,
			@RequestParam(required = false) LostReportStatus status
	) {
		if (page < 1 || pageSize < 1 || pageSize > 100) {
			throw new LostoryException(ErrorCode.INVALID_REQUEST);
		}
		return service.list(Long.valueOf(jwt.getSubject()), status, page, pageSize);
	}

	@GetMapping("/{reportId}")
	@Operation(summary = "분실 신고 상세 조회", description = "신고 소유자가 저장된 경로, 검색 반경과 센터 안내를 조회합니다.")
	public LostReportResponses.Detail detail(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt
	) {
		return service.detail(reportId, Long.valueOf(jwt.getSubject()));
	}

	@PatchMapping("/{reportId}")
	@Operation(summary = "분실 신고 수정", description = "신고의 매칭 입력을 수정하고 필요한 반경, 센터 안내와 후보를 다시 계산합니다.")
	public LostReportResponses.Update update(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody UpdateLostReportRequest request
	) {
		return service.update(reportId, Long.valueOf(jwt.getSubject()), request);
	}

	@PostMapping("/{reportId}:close")
	@Operation(summary = "분실 신고 종료", description = "신고 소유자가 빈 JSON 객체로 열린 신고를 종료합니다.")
	public LostReportResponses.Close close(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt,
			@RequestBody JsonNode body
	) {
		if (!body.isObject() || !body.isEmpty()) {
			throw new LostoryException(ErrorCode.INVALID_REQUEST);
		}
		return service.close(reportId, Long.valueOf(jwt.getSubject()));
	}
}
