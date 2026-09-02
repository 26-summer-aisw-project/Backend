package kr.lostory.backend.lostcenter.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.lostory.backend.lostcenter.application.LostCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/lost-centers")
@RequiredArgsConstructor
@Tag(name = "센터 관리", description = "관리자 전용 센터 디렉터리 관리 API")
@SecurityRequirement(name = "bearerAuth")
public class AdminLostCenterController {

    private final LostCenterService lostCenterService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "분실물 센터 생성", description = "관리자가 공개 센터 디렉터리에 활성 센터를 생성합니다.")
    public AdminLostCenterResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateLostCenterRequest request
    ) {
        return lostCenterService.create(Long.valueOf(jwt.getSubject()), request);
    }

    @PatchMapping("/{centerId}")
    @Operation(summary = "분실물 센터 수정", description = "관리자가 센터 연락처와 활성 상태 등 공개 정보를 수정합니다.")
    public AdminLostCenterResponse update(
            @PathVariable Long centerId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateLostCenterRequest request
    ) {
        return lostCenterService.update(centerId, Long.valueOf(jwt.getSubject()), request);
    }
}
