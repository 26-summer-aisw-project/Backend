package kr.lostory.backend.founditem.presentation;

import jakarta.validation.Valid;
import java.net.URI;
import kr.lostory.backend.founditem.application.FoundItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/v1/found-items")
public class FoundItemController {

    private final FoundItemService foundItemService;

    public FoundItemController(FoundItemService foundItemService) {
        this.foundItemService = foundItemService;
    }

    @PostMapping
    public ResponseEntity<FoundItemResponse> register(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateFoundItemRequest request
    ) {
        Long finderId = Long.valueOf(jwt.getSubject());
        FoundItemResponse response = foundItemService.register(request.toCommand(finderId));

        return ResponseEntity
                .created(URI.create("/api/v1/found-items/" + response.id()))
                .body(response);
    }

    @GetMapping("/{id}")
    public FoundItemResponse get(@PathVariable Long id) {
        return foundItemService.get(id);
    }
}