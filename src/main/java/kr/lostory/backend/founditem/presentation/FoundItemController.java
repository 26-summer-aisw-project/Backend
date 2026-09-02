package kr.lostory.backend.founditem.presentation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/found-items")
@Tag(name = "습득물", description = "사진 초안부터 사용자 인계 확정까지의 습득물 API")
@SecurityRequirement(name = "bearerAuth")
public class FoundItemController {

    private final FoundItemService foundItemService;
    private final FoundItemImageService imageService;

    public FoundItemController(FoundItemService foundItemService, FoundItemImageService imageService) {
        this.foundItemService = foundItemService;
        this.imageService = imageService;
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "습득물 초안 생성", description = "정확히 한 장의 사진으로 소유자 전용 DRAFT 습득물을 만들고 Vision 작업을 시작합니다.")
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
    @Operation(summary = "습득물 상세 조회", description = "소유자 또는 관리자가 등록 진행 상태와 Vision 제안을 조회합니다.")
    public FoundItemDetailResponse get(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return foundItemService.detail(id, Long.valueOf(jwt.getSubject()),
                roles != null && roles.contains("ADMIN"));
    }

    @PatchMapping("/{id}/registration")
    @Operation(summary = "습득물 등록 확정", description = "습득 위치, 시각, 분류, 공개 특징과 보관 방식을 저장해 등록을 확정합니다.")
    public FoundItemRegistrationResponse finalizeRegistration(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody FinalizeFoundItemRegistrationRequest request
    ) {
        return foundItemService.finalizeRegistration(id, Long.valueOf(jwt.getSubject()), request);
    }

    @PostMapping("/{id}:confirm-handover")
    @Operation(summary = "센터 인계 확정", description = "소유자가 선택한 센터에 습득물을 인계했음을 본문 없이 확정합니다.")
    public FoundItemRegistrationResponse confirmHandover(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) byte[] body
    ) {
        if (body != null && body.length > 0) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        return foundItemService.confirmHandover(id, Long.valueOf(jwt.getSubject()));
    }

    @GetMapping
    @Operation(summary = "내 습득물 목록 조회", description = "현재 사용자가 만든 습득물을 상태 필터와 페이지 조건으로 조회합니다.")
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

}
