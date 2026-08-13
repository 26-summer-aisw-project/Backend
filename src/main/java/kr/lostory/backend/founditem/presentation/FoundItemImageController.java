package kr.lostory.backend.founditem.presentation;

import java.util.List;
import kr.lostory.backend.founditem.application.FoundItemImageService;
import org.springframework.http.HttpStatus;
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
@RequestMapping("/api/v1/found-items/{foundItemId}/images")
public class FoundItemImageController {

    private final FoundItemImageService foundItemImageService;

    public FoundItemImageController(FoundItemImageService foundItemImageService) {
        this.foundItemImageService = foundItemImageService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<FoundItemImageResponse> upload(
            @PathVariable Long foundItemId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam("images") List<MultipartFile> images
    ) {
        Long requesterId = Long.valueOf(jwt.getSubject());
        return foundItemImageService.upload(foundItemId, requesterId, images);
    }

    @GetMapping
    public List<FoundItemImageResponse> getImages(@PathVariable Long foundItemId) {
        return foundItemImageService.getImages(foundItemId);
    }
}