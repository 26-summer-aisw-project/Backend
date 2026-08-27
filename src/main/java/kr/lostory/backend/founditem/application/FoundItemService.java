package kr.lostory.backend.founditem.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import kr.lostory.backend.audit.application.P0AuditService;
import kr.lostory.backend.config.FoundItemProperties;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.CenterHandover;
import kr.lostory.backend.founditem.domain.CenterHandoverRepository;
import kr.lostory.backend.founditem.domain.CenterHandoverStatus;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.FoundItemStatus;
import kr.lostory.backend.founditem.domain.HandoverStatus;
import kr.lostory.backend.founditem.domain.ItemFeature;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;
import kr.lostory.backend.founditem.domain.ItemFeatureRepository;
import kr.lostory.backend.founditem.domain.ItemFeatureSource;
import kr.lostory.backend.founditem.domain.ItemFeatureVisibility;
import kr.lostory.backend.founditem.domain.VisionStatus;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.founditem.presentation.FoundItemDetailResponse;
import kr.lostory.backend.founditem.presentation.FoundItemListResponse;
import kr.lostory.backend.founditem.presentation.FoundItemResponse;
import kr.lostory.backend.founditem.presentation.FinalizeFoundItemRegistrationRequest;
import kr.lostory.backend.founditem.presentation.FoundItemRegistrationResponse;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import kr.lostory.backend.lostreport.domain.LostReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FoundItemService {

    private final FoundItemRepository foundItemRepository;
    private final CenterHandoverRepository handoverRepository;
    private final ItemFeatureRepository featureRepository;
    private final LostCenterRepository centerRepository;
    private final LostReportRepository reportRepository;
    private final FoundItemProperties properties;
    private final Clock clock;
    private final P0AuditService audit;
    private final JdbcTemplate jdbc;

    public FoundItemService(
            FoundItemRepository foundItemRepository,
            CenterHandoverRepository handoverRepository,
            ItemFeatureRepository featureRepository,
            LostCenterRepository centerRepository,
            LostReportRepository reportRepository,
            FoundItemProperties properties,
            Clock clock,
            P0AuditService audit,
            JdbcTemplate jdbc
    ) {
        this.foundItemRepository = foundItemRepository;
        this.handoverRepository = handoverRepository;
        this.featureRepository = featureRepository;
        this.centerRepository = centerRepository;
        this.reportRepository = reportRepository;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional
    public FoundItemRegistrationResponse finalizeRegistration(
            Long id,
            Long requesterId,
            FinalizeFoundItemRegistrationRequest request
    ) {
        HandoverSnapshot admission = currentHandover(id);
        FoundItem item = foundItemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!item.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!item.isRegistrationMutable()) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        HandoverSnapshot current = currentHandover(id);
        rejectStaleAdmission(admission, current);
        boolean finalizingDraft = item.getStatus() == FoundItemStatus.DRAFT;
        Instant databaseNow = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
        boolean wasMatchingEligible = matchingEligible(item, databaseNow);
        boolean withdrawingHandover = item.getStorageMethod() == StorageMethod.HANDED_TO_CENTER
                && request.storageMethod() != StorageMethod.HANDED_TO_CENTER
                && (item.getStatus() == FoundItemStatus.PENDING_HANDOVER
                        || item.getHandoverStatus() == HandoverStatus.USER_CONFIRMED);

        String category = request.category().trim();
        String color = request.confirmedFeatures().color();
        String publicDescription = request.confirmedFeatures().publicDescription().trim();
        String storageDescription = trimToNull(request.storageDescription());
        Long centerId = parseCenterId(request.centerId());
        validateRegistrationStorage(request, storageDescription, centerId);

        boolean matchingFieldsUnchanged = item.hasMatchingFields(
                category,
                request.foundAt(),
                request.foundLocation().latitude(),
                request.foundLocation().longitude());
        boolean confirmedFeaturesUnchanged = confirmedFeature(item.getId(), ItemFeatureKind.COLOR)
                .filter(color::equals).isPresent()
                && confirmedFeature(item.getId(), ItemFeatureKind.PUBLIC_DESCRIPTION)
                .filter(publicDescription::equals).isPresent();

        boolean registrationUnchanged = matchingFieldsUnchanged
                && confirmedFeaturesUnchanged
                && item.getStorageMethod() == request.storageMethod()
                && Objects.equals(item.getStorageDescription(), storageDescription)
                && Objects.equals(item.getCenterId(), centerId);
        if (registrationUnchanged && handoverStateUnchanged(item, current)) {
            return FoundItemRegistrationResponse.from(item);
        }
        if (current != null && current.status() == CenterHandoverStatus.CENTER_CONFIRMED) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }

        item.finalizeRegistration(
                category,
                request.foundAt(),
                request.foundLocation().latitude(),
                request.foundLocation().longitude(),
                request.storageMethod(),
                storageDescription,
                centerId,
                publicDescription,
                clock.instant(),
                properties.ttl());
        replaceConfirmedFeatures(item.getId(), color, publicDescription);
        boolean isMatchingEligible = matchingEligible(item, databaseNow);
        if (!matchingFieldsUnchanged || !confirmedFeaturesUnchanged
                || wasMatchingEligible != isMatchingEligible) {
            reportRepository.markOpenCandidatesStale();
        }
        if (finalizingDraft) {
            audit.foundItemFinalized(requesterId, item.getId());
        }
        if (withdrawingHandover) {
            audit.handoverWithdrawn(requesterId, item.getId());
        }
        supersede(current);
        return FoundItemRegistrationResponse.from(item);
    }

    @Transactional
    public FoundItemRegistrationResponse confirmHandover(Long id, Long requesterId) {
        HandoverSnapshot admission = currentHandover(id);
        FoundItem item = foundItemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!item.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        HandoverSnapshot current = currentHandover(id);
        rejectStaleAdmission(admission, current);
        if (current != null && current.status() == CenterHandoverStatus.CENTER_CONFIRMED) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
        if (item.getStatus() != FoundItemStatus.PENDING_HANDOVER
                || item.getStorageMethod() != StorageMethod.HANDED_TO_CENTER
                || item.getHandoverStatus() != HandoverStatus.NONE
                || item.getCenterId() == null
                || item.getHandedAt() != null
                || !centerRepository.isEligibleForHandover(
                        item.getCenterId(), item.getFoundLatitude(), item.getFoundLongitude())) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
        Instant confirmedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        item.confirmHandover(confirmedAt);
        reportRepository.markOpenCandidatesStale();
        supersede(current);
        handoverRepository.save(new CenterHandover(item.getId(), item.getCenterId(), confirmedAt));
        audit.handoverUserConfirmed(requesterId, item.getId());
        return FoundItemRegistrationResponse.from(item);
    }

    private HandoverSnapshot currentHandover(Long foundItemId) {
        List<HandoverSnapshot> rows = jdbc.query("""
                SELECT id, status FROM center_handovers
                WHERE found_item_id = ? AND superseded_at IS NULL
                """, (result, row) -> new HandoverSnapshot(
                        result.getLong("id"), CenterHandoverStatus.valueOf(result.getString("status"))),
                foundItemId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void rejectStaleAdmission(HandoverSnapshot admission, HandoverSnapshot current) {
        if (admission != null
                && admission.status() == CenterHandoverStatus.USER_CONFIRMED
                && !Objects.equals(admission, current)) {
            throw new LostoryException(ErrorCode.INVALID_STATE);
        }
    }

    private boolean handoverStateUnchanged(FoundItem item, HandoverSnapshot current) {
        return current != null && switch (current.status()) {
            case USER_CONFIRMED -> item.getHandoverStatus() == HandoverStatus.USER_CONFIRMED;
            case CENTER_CONFIRMED -> item.getHandoverStatus() == HandoverStatus.CENTER_CONFIRMED;
            case REJECTED -> false;
        };
    }

    private void supersede(HandoverSnapshot current) {
        if (current != null) {
            CenterHandover handover = handoverRepository.findById(current.id())
                    .orElseThrow(() -> new LostoryException(ErrorCode.INVALID_STATE));
            handover.supersede(clock.instant().truncatedTo(ChronoUnit.MICROS));
        }
    }

    private record HandoverSnapshot(Long id, CenterHandoverStatus status) {
    }

    private void validateRegistrationStorage(
            FinalizeFoundItemRegistrationRequest request,
            String storageDescription,
            Long centerId
    ) {
        switch (request.storageMethod()) {
            case LEFT_IN_PLACE -> {
                if (storageDescription != null || centerId != null) {
                    throw new LostoryException(ErrorCode.INVALID_REQUEST);
                }
            }
            case MOVED_TO_SAFE_PLACE -> {
                if (storageDescription == null || centerId != null) {
                    throw new LostoryException(ErrorCode.INVALID_REQUEST);
                }
            }
            case HANDED_TO_CENTER -> {
                if (storageDescription != null || centerId == null
                        || !centerRepository.isEligibleForHandover(
                                centerId,
                                request.foundLocation().latitude(),
                                request.foundLocation().longitude())) {
                    throw new LostoryException(ErrorCode.INVALID_REQUEST);
                }
            }
        }
    }

    private Long parseCenterId(String centerId) {
        if (!StringUtils.hasText(centerId)) {
            return null;
        }
        try {
            return Long.valueOf(centerId);
        } catch (NumberFormatException exception) {
            throw new LostoryException(ErrorCode.INVALID_REQUEST);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private java.util.Optional<String> confirmedFeature(Long itemId, ItemFeatureKind kind) {
        return featureRepository.findByItemIdAndKindAndSourceAndVisibilityOrderByOrdinalAscIdAsc(
                        itemId, kind, ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW).stream()
                .map(ItemFeature::getFeatureValue)
                .findFirst();
    }

    private boolean matchingEligible(FoundItem item, Instant databaseNow) {
        return item.getStatus() == FoundItemStatus.ACTIVE
                && item.getExpiredAt() != null
                && item.getExpiredAt().isAfter(databaseNow);
    }

    private void replaceConfirmedFeatures(Long itemId, String color, String publicDescription) {
        List<ItemFeatureKind> kinds = List.of(ItemFeatureKind.COLOR, ItemFeatureKind.PUBLIC_DESCRIPTION);
        featureRepository.deleteByItemIdAndSourceAndKinds(itemId, ItemFeatureSource.FINDER, kinds);
        featureRepository.saveAll(List.of(
                new ItemFeature(itemId, ItemFeatureKind.COLOR, color, (short) 1,
                        ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW, null),
                new ItemFeature(itemId, ItemFeatureKind.PUBLIC_DESCRIPTION, publicDescription, (short) 1,
                        ItemFeatureSource.FINDER, ItemFeatureVisibility.CANDIDATE_VIEW, null)));
    }

    @Transactional
    public FoundItemResponse register(CreateFoundItemCommand command) {
        validateStorage(command);
        validateLocation(command);

        FoundItem foundItem = new FoundItem(
                command.finderId(),
                command.name(),
                command.category(),
                command.description(),
                command.foundAt(),
                command.foundLatitude(),
                command.foundLongitude(),
                command.foundAddress(),
                command.foundLocationDetail(),
                command.storageMethod(),
                command.storageDescription(),
                command.handoverPlaceName()
        );

        FoundItem savedFoundItem = foundItemRepository.save(foundItem);

        return FoundItemResponse.from(savedFoundItem);
    }

    @Transactional(readOnly = true)
    public FoundItemResponse get(Long id, Long requesterId) {
        FoundItem foundItem = foundItemRepository.findById(id)
                .orElseThrow(() -> new LostoryException(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        "습득물이 찾아지지 않았습니다."
                ));

        if (!foundItem.getFinderId().equals(requesterId)) {
            throw new LostoryException(
                    ErrorCode.FORBIDDEN,
                    "본인이 등록한 습득물만 조회할 수 있습니다."
            );
        }

        return FoundItemResponse.from(foundItem);
    }

    @Transactional(readOnly = true)
    public FoundItemDetailResponse detail(Long id, Long requesterId, boolean admin) {
        FoundItem item = foundItemRepository.findById(id)
                .orElseThrow(() -> new LostoryException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!admin && !item.getFinderId().equals(requesterId)) {
            throw new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return FoundItemDetailResponse.from(item, suggestion(item));
    }

    @Transactional(readOnly = true)
    public FoundItemListResponse list(Long requesterId, FoundItemStatus status, int page, int pageSize) {
        PageRequest pageable = PageRequest.of(page - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FoundItem> result = status == null
                ? foundItemRepository.findByFinderId(requesterId, pageable)
                : foundItemRepository.findByFinderIdAndStatus(requesterId, status, pageable);
        return FoundItemListResponse.from(result, page, pageSize);
    }

    private FoundItemDetailResponse.VisionSuggestion suggestion(FoundItem item) {
        if (item.getVisionStatus() != VisionStatus.READY) {
            return null;
        }
        String color = null;
        String label = null;
        for (ItemFeature feature : featureRepository
                .findByItemIdAndSourceAndVisibilityOrderByKindAscOrdinalAsc(
                        item.getId(), ItemFeatureSource.AI, ItemFeatureVisibility.MATCH_ONLY)) {
            if (feature.getKind() == ItemFeatureKind.COLOR && color == null) {
                color = feature.getFeatureValue();
            }
            if (feature.getKind() == ItemFeatureKind.LABEL && label == null) {
                label = feature.getFeatureValue();
            }
        }
        String publicDescription = color == null ? label : label == null ? color : color + " " + label;
        return publicDescription == null
                ? null
                : new FoundItemDetailResponse.VisionSuggestion(color, publicDescription);
    }

    private void validateStorage(CreateFoundItemCommand command) {
        if (command.storageMethod() == StorageMethod.LEFT_IN_PLACE) {
            if (StringUtils.hasText(command.storageDescription())
                    || StringUtils.hasText(command.handoverPlaceName())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "storageDescription and handoverPlaceName must be empty when storageMethod is LEFT_IN_PLACE."
                );
            }
            return;
        }

        if (command.storageMethod() == StorageMethod.MOVED_TO_SAFE_PLACE) {
            if (!StringUtils.hasText(command.storageDescription())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "storageDescription is required when storageMethod is MOVED_TO_SAFE_PLACE."
                );
            }

            if (StringUtils.hasText(command.handoverPlaceName())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "handoverPlaceName must be empty when storageMethod is MOVED_TO_SAFE_PLACE."
                );
            }
            return;
        }

        if (command.storageMethod() == StorageMethod.HANDED_TO_CENTER) {
            if (StringUtils.hasText(command.storageDescription())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "storageDescription must be empty when storageMethod is HANDED_TO_CENTER."
                );
            }

            if (!StringUtils.hasText(command.handoverPlaceName())) {
                throw new LostoryException(
                        ErrorCode.INVALID_REQUEST,
                        "handoverPlaceName is required when storageMethod is HANDED_TO_CENTER."
                );
            }
        }
    }
    private void validateLocation(CreateFoundItemCommand command) {
        if (command.foundLatitude() == null || command.foundLongitude() == null) {
            throw new LostoryException(
                    ErrorCode.INVALID_REQUEST,
                    "foundLatitude and foundLongitude are required."
            );
        }
    }
}
