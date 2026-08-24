package kr.lostory.backend.founditem.presentation;

import java.util.List;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.founditem.application.FoundItemImageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/found-items/{foundItemId}/image")
public class FoundItemImageController {

    private final FoundItemImageService service;

    public FoundItemImageController(FoundItemImageService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FoundItemImageResponse upload(
            @PathVariable Long foundItemId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("image") List<MultipartFile> images
    ) {
        if (images.size() != 1) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        return service.upload(foundItemId, Long.valueOf(jwt.getSubject()), images.getFirst());
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
