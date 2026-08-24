package kr.lostory.backend.lostcenter.presentation;

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
public class LostCenterController {

    private final LostCenterService lostCenterService;

    @GetMapping("/lost-centers")
    public LostCenterListResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String q
    ) {
        return lostCenterService.list(page, pageSize, q);
    }

    @GetMapping("/lost-centers/nearby")
    public NearbyLostCenterListResponse findNearby(
            @RequestParam BigDecimal latitude,
            @RequestParam BigDecimal longitude
    ) {
        return lostCenterService.findNearby(latitude, longitude);
    }

    @GetMapping("/found-items/{foundItemId}/nearby-centers")
    public NearbyLostCenterListResponse findNearbyByFoundItem(
            @PathVariable Long foundItemId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return lostCenterService.findNearbyByFoundItem(foundItemId, Long.valueOf(jwt.getSubject()));
    }
}
