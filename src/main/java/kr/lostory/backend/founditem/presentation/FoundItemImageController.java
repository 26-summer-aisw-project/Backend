package kr.lostory.backend.founditem.presentation;

import java.util.List;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.founditem.application.FoundItemImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/found-items/{foundItemId}/image")
public class FoundItemImageController {

    private final FoundItemImageService service;

    public FoundItemImageController(FoundItemImageService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<byte[]> get(@PathVariable Long foundItemId, @AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        ObjectStorage.StoredObject object = service.getCurrent(
                foundItemId, Long.valueOf(jwt.getSubject()), roles != null && roles.contains("ADMIN"));
        return ResponseEntity.ok()
                .header("Content-Type", object.contentType())
                .body(object.bytes());
    }
}
