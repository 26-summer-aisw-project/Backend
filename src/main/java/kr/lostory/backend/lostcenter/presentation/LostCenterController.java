package kr.lostory.backend.lostcenter.presentation;

import java.util.List;
import kr.lostory.backend.lostcenter.application.LostCenterService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/found-items")
public class LostCenterController {

    private final LostCenterService lostCenterService;

    public LostCenterController(LostCenterService lostCenterService) {
        this.lostCenterService = lostCenterService;
    }

    @GetMapping("/{foundItemId}/nearby-lost-centers")
    public List<NearbyLostCenterResponse> findNearbyByFoundItem(
            @PathVariable Long foundItemId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        Long requesterId = Long.valueOf(jwt.getSubject());

        return lostCenterService.findNearbyByFoundItem(foundItemId, requesterId);
    }
}