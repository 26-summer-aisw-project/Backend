package kr.lostory.backend.partner.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.partner.application.PartnerCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Tag(name = "파트너 센터", description = "파트너 센터 승인과 센터 담당자 활성화 API")
public class PartnerCenterController {

    private final PartnerCenterService service;

    @PostMapping("/api/v1/admin/partner-centers")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "파트너 센터 신청 생성", description = "ADMIN이 기존 센터와 새 담당자 계정을 검토 대기 파트너십으로 연결합니다.")
    public PartnerCenterResponses.Created create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePartnerCenterRequest request) {
        try {
            return service.create(Long.valueOf(jwt.getSubject()), request);
        } catch (NumberFormatException exception) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
    }

    @PostMapping("/api/v1/admin/partner-centers/{partnershipId}:approve")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "파트너 센터 승인", description = "ADMIN이 파트너십을 승인하고 외부 운영 절차용 암호화 활성화 전달 자료를 생성합니다.")
    public PartnerCenterResponses.Approved approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long partnershipId) {
        return service.approve(Long.valueOf(jwt.getSubject()), partnershipId);
    }

    @PostMapping("/api/v1/partner-manager-activations/{activationToken}")
    @Operation(summary = "센터 담당자 활성화", description = "공개 경로에서 일회성 토큰과 새 비밀번호를 검증해 센터 담당자 계정을 활성화합니다.")
    public PartnerCenterResponses.Activated activate(
            @PathVariable String activationToken,
            @Valid @RequestBody ActivatePartnerManagerRequest request) {
        return service.activate(activationToken, request);
    }
}
