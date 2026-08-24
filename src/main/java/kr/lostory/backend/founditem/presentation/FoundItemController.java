package kr.lostory.backend.founditem.presentation;

import java.util.List;
import java.util.Set;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.application.FoundItemImageService;
import kr.lostory.backend.founditem.application.FoundItemService;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;

@RestController
@RequestMapping("/api/v1/found-items")
public class FoundItemController {

    private final FoundItemService foundItemService;
    private final FoundItemImageService imageService;

    public FoundItemController(FoundItemService foundItemService, FoundItemImageService imageService) {
        this.foundItemService = foundItemService;
        this.imageService = imageService;
    }

    @PostMapping
    public void retiredRegister() {
        throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public FoundItemDraftResponse createDraft(
            @AuthenticationPrincipal Jwt jwt,
            @RequestPart("image") List<MultipartFile> images,
            MultipartHttpServletRequest request
    ) {
        if (images.size() != 1 || !request.getMultiFileMap().keySet().equals(Set.of("image"))
                || !request.getParameterMap().isEmpty()) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        FoundItem item = imageService.createDraft(Long.valueOf(jwt.getSubject()), images.getFirst());
        return FoundItemDraftResponse.from(item);
    }

    @GetMapping("/{id}")
    public FoundItemDetailResponse get(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return foundItemService.detail(id, Long.valueOf(jwt.getSubject()),
                roles != null && roles.contains("ADMIN"));
    }

    @GetMapping
    public FoundItemListResponse list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) FoundItemStatus status
    ) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        return foundItemService.list(Long.valueOf(jwt.getSubject()), status, page, pageSize);
    }

    @PostMapping("/{id}/images")
    public void retiredImageUpload(@PathVariable Long id) {
        throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @GetMapping("/{id}/images")
    public void retiredImageList(@PathVariable Long id) {
        throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
