package kr.lostory.backend.founditem.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.founditem.application.FoundItemImageService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/v1/found-items/{foundItemId}/image")
@Tag(name = "습득물 이미지", description = "현재 습득물 사진 조회와 교체 API")
@SecurityRequirement(name = "bearerAuth")
public class FoundItemImageController {

    private final FoundItemImageService service;

    public FoundItemImageController(FoundItemImageService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "습득물 사진 조회", description = "소유자 또는 관리자가 현재 사진을 원본 Content-Type과 바이트로 조회합니다.")
    public ResponseEntity<byte[]> get(@PathVariable Long foundItemId, @AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        ObjectStorage.StoredObject object = service.getCurrent(
                foundItemId, Long.valueOf(jwt.getSubject()), roles != null && roles.contains("ADMIN"));
        return ResponseEntity.ok()
                .header("Content-Type", object.contentType())
                .body(object.bytes());
    }

    @PutMapping
    @Operation(summary = "습득물 사진 교체", description = "소유자가 현재 사진 한 장을 원자적으로 교체하고 새 Vision 세대를 시작합니다.")
    public FoundItemImageResponse replace(
            @PathVariable Long foundItemId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("image") List<MultipartFile> images,
            MultipartHttpServletRequest request
    ) {
        if (images.size() != 1 || !request.getMultiFileMap().keySet().equals(Set.of("image"))
                || !request.getParameterMap().isEmpty()) {
            throw new kr.lostory.backend.common.exception.LostoryException(
                    kr.lostory.backend.common.exception.ErrorCode.INVALID_REQUEST);
        }
        return service.upload(foundItemId, Long.valueOf(jwt.getSubject()), images.getFirst());
    }
}
