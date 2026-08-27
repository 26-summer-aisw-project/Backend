package kr.lostory.backend.returnrecord.presentation;

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
public class ReturnController {

    private final ReturnService service;

    public ReturnController(ReturnService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
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
