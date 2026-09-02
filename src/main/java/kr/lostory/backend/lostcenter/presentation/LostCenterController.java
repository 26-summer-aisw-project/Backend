package kr.lostory.backend.lostcenter.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import kr.lostory.backend.lostcenter.application.LostCenterService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "분실물 센터", description = "센터 디렉터리와 인근 인계 후보 조회 API")
@SecurityRequirement(name = "bearerAuth")
public class LostCenterController {

    private final LostCenterService lostCenterService;

    @GetMapping("/lost-centers")
    @Operation(summary = "분실물 센터 목록 조회", description = "활성 센터 디렉터리를 검색어와 페이지 조건으로 조회합니다.")
    public LostCenterListResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q
    ) {
        return lostCenterService.list(page, pageSize, q);
    }

    @GetMapping("/lost-centers/nearby")
    @Operation(summary = "인근 분실물 센터 조회", description = "위도와 경도를 기준으로 인계 가능한 활성 센터 후보를 거리순으로 조회합니다.")
    public NearbyLostCenterListResponse findNearby(
            @RequestParam BigDecimal latitude,
            @RequestParam BigDecimal longitude
    ) {
        return lostCenterService.findNearby(latitude, longitude);
    }

    @GetMapping("/found-items/{foundItemId}/nearby-centers")
    @Operation(summary = "습득물 인근 센터 조회", description = "습득물에 저장된 위치를 기준으로 인계 가능한 센터 후보를 다시 조회합니다.")
    public NearbyLostCenterListResponse findNearbyByFoundItem(
            @PathVariable Long foundItemId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return lostCenterService.findNearbyByFoundItem(foundItemId, Long.valueOf(jwt.getSubject()));
    }
}
