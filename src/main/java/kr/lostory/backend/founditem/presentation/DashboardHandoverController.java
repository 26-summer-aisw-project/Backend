package kr.lostory.backend.founditem.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.application.DashboardHandoverService;
import kr.lostory.backend.founditem.domain.CenterHandoverStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/handovers")
@Tag(name = "센터 인계 대시보드", description = "활성 센터 담당자의 인계 확인 대기열과 결정 API")
@SecurityRequirement(name = "bearerAuth")
public class DashboardHandoverController {

    private final DashboardHandoverService service;

    public DashboardHandoverController(DashboardHandoverService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "인계 확인 대기열", description = "활성 파트너십에 지정된 담당자 자신의 센터 항목만 조회합니다.")
    public DashboardHandoverResponses.ListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "USER_CONFIRMED") CenterHandoverStatus status
    ) {
        if (status != CenterHandoverStatus.USER_CONFIRMED) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        return service.list(Long.valueOf(jwt.getSubject()), status);
    }

    @PostMapping("/{handoverId}:accept")
    @Operation(summary = "센터 인계 수락", description = "비공개 특징은 요청 중 실물 확인에만 사용하며 저장, 감사, 응답하지 않습니다.")
    public DashboardHandoverResponses.AcceptResponse accept(
            @PathVariable Long handoverId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AcceptHandoverRequest request
    ) {
        return service.accept(handoverId, Long.valueOf(jwt.getSubject()), request.privateFeatures());
    }

    @PostMapping("/{handoverId}:reject")
    @Operation(summary = "센터 인계 거절", description = "거절 사유와 결정을 기록하고 사용자의 인계 주장은 보존합니다.")
    public DashboardHandoverResponses.RejectResponse reject(
            @PathVariable Long handoverId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RejectHandoverRequest request
    ) {
        return service.reject(handoverId, Long.valueOf(jwt.getSubject()), request.reason());
    }
}
