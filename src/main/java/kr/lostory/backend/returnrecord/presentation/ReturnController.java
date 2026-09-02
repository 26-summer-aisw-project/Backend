package kr.lostory.backend.returnrecord.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.returnrecord.application.ReturnService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/returns")
@Tag(name = "반환 기록", description = "활성 센터 담당자의 안전한 반환 기록 API")
@SecurityRequirement(name = "bearerAuth")
public class ReturnController {

    private final ReturnService service;

    public ReturnController(ReturnService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "반환 기록 생성", description = "센터 확인 인계와 열린 분실 신고의 관계를 검증하고 다섯 필드 반환 결과만 생성합니다.")
    public RecordReturnResponse record(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RecordReturnRequest request
    ) {
        try {
            return service.record(
                    Long.valueOf(jwt.getSubject()),
                    Long.valueOf(request.itemId()),
                    Long.valueOf(request.reportId()));
        } catch (NumberFormatException exception) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
    }
}
