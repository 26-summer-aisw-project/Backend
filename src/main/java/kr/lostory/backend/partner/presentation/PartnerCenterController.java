package kr.lostory.backend.partner.presentation;

import jakarta.validation.Valid;
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
public class PartnerCenterController {

    private final PartnerCenterService service;

    @PostMapping("/api/v1/admin/partner-centers")
    @ResponseStatus(HttpStatus.CREATED)
    public PartnerCenterResponses.Created create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreatePartnerCenterRequest request) {
        return service.create(Long.valueOf(jwt.getSubject()), request);
    }

    @PostMapping("/api/v1/admin/partner-centers/{partnershipId}:approve")
    public PartnerCenterResponses.Approved approve(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long partnershipId) {
        return service.approve(Long.valueOf(jwt.getSubject()), partnershipId);
    }

    @PostMapping("/api/v1/partner-manager-activations/{activationToken}")
    public PartnerCenterResponses.Activated activate(
            @PathVariable String activationToken,
            @Valid @RequestBody ActivatePartnerManagerRequest request) {
        return service.activate(activationToken, request);
    }
}
