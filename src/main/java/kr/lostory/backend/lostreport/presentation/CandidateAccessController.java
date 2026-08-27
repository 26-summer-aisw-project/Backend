package kr.lostory.backend.lostreport.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Locale;
import java.util.UUID;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.lostreport.application.UnlockedCandidateService;
import kr.lostory.backend.point.application.CandidateAccessService;
import kr.lostory.backend.point.presentation.CandidateAccessResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lost-reports")
@Tag(name = "후보 상세 열람", description = "포인트 기반 후보 상세 열람 API")
@SecurityRequirement(name = "bearerAuth")
public class CandidateAccessController {

	private final CandidateAccessService accessService;
	private final UnlockedCandidateService candidateService;

	public CandidateAccessController(CandidateAccessService accessService,
			UnlockedCandidateService candidateService) {
		this.accessService = accessService;
		this.candidateService = candidateService;
	}

	@PostMapping("/{reportId}/candidate-accesses")
	@Operation(summary = "후보 상세 열람 권한 획득", description = "신고별 최초 요청에서 포인트 한 점을 차감하고 기존 열람은 유효한 멱등 키로 추가 차감 없이 replayed=true 응답을 재생합니다. 사용한 키를 다른 신고나 사용자가 재사용하면 409 POINT-001입니다.")
	public CandidateAccessResponse unlock(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") String idempotencyKey
	) {
		return accessService.unlock(reportId, Long.valueOf(jwt.getSubject()), parse(idempotencyKey));
	}

	@GetMapping("/{reportId}/candidates/unlocked")
	@Operation(summary = "열람 권한을 획득한 후보 상세 조회", description = "소유자에게 허용된 후보 상세만 반환하며 thumbnailUrl만 비공개 사진의 단기 서명 URL로 제공합니다.")
	public ResponseEntity<UnlockedCandidateResponse> candidates(
			@PathVariable Long reportId,
			@AuthenticationPrincipal Jwt jwt
	) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(candidateService.list(reportId, Long.valueOf(jwt.getSubject())));
	}

	private UUID parse(String value) {
		try {
			UUID parsed = UUID.fromString(value);
			if (!parsed.toString().equals(value.toLowerCase(Locale.ROOT))
					|| parsed.variant() != 2 || parsed.version() < 1 || parsed.version() > 5) {
				throw new IllegalArgumentException();
			}
			return parsed;
		} catch (IllegalArgumentException exception) {
			throw new LostoryException(ErrorCode.INVALID_REQUEST);
		}
	}
}
