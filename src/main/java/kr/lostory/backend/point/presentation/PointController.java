package kr.lostory.backend.point.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.point.application.PointQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
@Tag(name = "포인트", description = "현재 사용자의 포인트 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class PointController {

	private final PointQueryService service;

	public PointController(PointQueryService service) {
		this.service = service;
	}

	@GetMapping("/balance")
	@Operation(summary = "포인트 잔액 조회", description = "현재 사용자의 포인트 잔액을 반환합니다.")
	public PointResponses.Balance balance(@AuthenticationPrincipal Jwt jwt) {
		return service.balance(Long.valueOf(jwt.getSubject()));
	}

	@GetMapping("/ledger")
	@Operation(summary = "포인트 거래 내역 조회", description = "현재 사용자의 불변 거래 내역을 최신순으로 반환합니다.")
	public PointResponses.LedgerList ledger(
			@AuthenticationPrincipal Jwt jwt,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize
	) {
		if (page < 1 || pageSize < 1 || pageSize > 100) {
			throw new LostoryException(ErrorCode.INVALID_REQUEST);
		}
		return service.ledger(Long.valueOf(jwt.getSubject()), page, pageSize);
	}
}
