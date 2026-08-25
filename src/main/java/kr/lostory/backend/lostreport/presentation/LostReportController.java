package kr.lostory.backend.lostreport.presentation;

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
public class LostReportController {

	private final LostReportApiService service;
	private final LostReportCandidateService candidateService;

	public LostReportController(LostReportApiService service, LostReportCandidateService candidateService) {
		this.service = service;
		this.candidateService = candidateService;
	}

	@GetMapping("/{reportId}/candidates")
	public LostReportCandidateResponse candidates(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt
	) {
		return candidateService.candidates(reportId, Long.valueOf(jwt.getSubject()));
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public LostReportResponses.Create create(
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody CreateLostReportRequest request
	) {
		return service.create(Long.valueOf(jwt.getSubject()), request);
	}

	@GetMapping
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
	public LostReportResponses.Detail detail(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt
	) {
		return service.detail(reportId, Long.valueOf(jwt.getSubject()));
	}

	@PatchMapping("/{reportId}")
	public LostReportResponses.Update update(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt,
			@Valid @RequestBody UpdateLostReportRequest request
	) {
		return service.update(reportId, Long.valueOf(jwt.getSubject()), request);
	}

	@PostMapping("/{reportId}:close")
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
