package kr.lostory.backend.lostcenter.presentation;

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
public class AdminLostCenterController {

    private final LostCenterService lostCenterService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminLostCenterResponse create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateLostCenterRequest request
    ) {
        return lostCenterService.create(Long.valueOf(jwt.getSubject()), request);
    }

    @PatchMapping("/{centerId}")
    public AdminLostCenterResponse update(
            @PathVariable Long centerId,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody UpdateLostCenterRequest request
    ) {
        return lostCenterService.update(centerId, Long.valueOf(jwt.getSubject()), request);
    }
}
