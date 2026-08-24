package kr.lostory.backend.lostcenter.application;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.lostory.backend.lostcenter.domain.LostCenter;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class LostCenterCsvInitializer implements ApplicationRunner {

    private static final String MASTER_RESOURCE = "data/lost_centers_master.csv";
    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);
    private static final Set<String> APPROVED_VERIFICATION_STATUSES = Set.of(
            "official_verified",
            "official_board_verified",
            "official_local_verified"
    );
    private static final Set<String> REQUIRED_HEADERS = Set.of(
            "center_id",
            "name",
            "parent_place",
            "phone",
            "address",
            "lat",
            "lng",
            "detail_location",
            "operating_hours",
            "handoff_available",
            "verification_status"
    );

    private final LostCenterRepository lostCenterRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments ignored) {
        List<CenterData> centers = readCenters();
        List<String> eligibleCenterKeys = centers.stream().map(CenterData::centerKey).toList();
        lostCenterRepository.deactivateCsvManagedNotIn(eligibleCenterKeys);
        Map<String, LostCenter> existingCenters = lostCenterRepository.findAllBySourceKeyIn(
                        eligibleCenterKeys
                )
                .stream()
                .collect(Collectors.toMap(LostCenter::getSourceKey, Function.identity()));
        List<LostCenter> newCenters = new ArrayList<>();

        for (CenterData center : centers) {
            LostCenter existingCenter = existingCenters.get(center.centerKey());
            if (existingCenter == null) {
                newCenters.add(center.toEntity());
            } else {
                center.synchronize(existingCenter);
            }
        }

        lostCenterRepository.saveAll(newCenters);
    }

    private List<CenterData> readCenters() {
        ClassPathResource resource = new ClassPathResource(MASTER_RESOURCE);
        try (PushbackReader reader = new PushbackReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8),
                1
        )) {
            discardUtf8Bom(reader);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .get();
            try (CSVParser parser = format.parse(reader)) {
                validateHeaders(parser.getHeaderNames());
                Set<String> centerKeys = new HashSet<>();
                List<CenterData> centers = new ArrayList<>();
                for (CSVRecord record : parser) {
                    if (!record.isConsistent()) {
                        throw invalidRecord(record, "열 개수가 헤더와 일치하지 않습니다.");
                    }
                    CenterData center = CenterData.from(record);
                    if (!centerKeys.add(center.centerKey())) {
                        throw invalidRecord(record, "center_id가 중복되었습니다.");
                    }
                    if (center.isEligibleForRecommendation()) {
                        centers.add(center);
                    }
                }
                if (centers.isEmpty()) {
                    throw new IllegalStateException("분실물센터 마스터 CSV에 데이터가 없습니다: " + MASTER_RESOURCE);
                }
                return centers;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("분실물센터 마스터 CSV를 읽을 수 없습니다: " + MASTER_RESOURCE, exception);
        }
    }

    private static void discardUtf8Bom(PushbackReader reader) throws IOException {
        int firstCharacter = reader.read();
        if (firstCharacter != '\uFEFF' && firstCharacter != -1) {
            reader.unread(firstCharacter);
        }
    }

    private static void validateHeaders(List<String> headers) {
        if (!headers.containsAll(REQUIRED_HEADERS)) {
            throw new IllegalStateException("분실물센터 마스터 CSV의 필수 헤더가 없습니다: " + REQUIRED_HEADERS);
        }
    }

    private static IllegalStateException invalidRecord(CSVRecord record, String reason) {
        return new IllegalStateException("분실물센터 마스터 CSV " + record.getRecordNumber() + "행: " + reason);
    }

    private record CenterData(
            String centerKey,
            String name,
            String parentPlace,
            String phoneNumber,
            String address,
            String detailLocation,
            BigDecimal latitude,
            BigDecimal longitude,
            String operatingHours,
            String handoffAvailable,
            String verificationStatus
    ) {
        private static CenterData from(CSVRecord record) {
            return new CenterData(
                    required(record, "center_id"),
                    required(record, "name"),
                    optional(record, "parent_place"),
                    required(record, "phone"),
                    required(record, "address"),
                    optional(record, "detail_location"),
                    coordinate(record, "lat", MAX_LATITUDE),
                    coordinate(record, "lng", MAX_LONGITUDE),
                    required(record, "operating_hours"),
                    required(record, "handoff_available"),
                    required(record, "verification_status")
            );
        }

        private LostCenter toEntity() {
            return new LostCenter(
                    centerKey,
                    name,
                    parentPlace,
                    address,
                    detailLocation,
                    latitude,
                    longitude,
                    phoneNumber,
                    operatingHours,
                    verificationStatus
            );
        }

        private void synchronize(LostCenter center) {
            center.synchronize(
                    name,
                    parentPlace,
                    address,
                    detailLocation,
                    latitude,
                    longitude,
                    phoneNumber,
                    operatingHours,
                    verificationStatus
            );
        }

        private boolean isEligibleForRecommendation() {
            return handoffAvailable.equals("yes")
                    && APPROVED_VERIFICATION_STATUSES.contains(verificationStatus);
        }

        private static String required(CSVRecord record, String header) {
            String value = optional(record, header);
            if (value == null) {
                throw invalidRecord(record, header + " 값이 비어 있습니다.");
            }
            return value;
        }

        private static String optional(CSVRecord record, String header) {
            String value = record.get(header).strip();
            return value.isEmpty() ? null : value;
        }

        private static BigDecimal coordinate(CSVRecord record, String header, BigDecimal maximum) {
            try {
                BigDecimal value = new BigDecimal(required(record, header));
                if (value.abs().compareTo(maximum) > 0) {
                    throw invalidRecord(record, header + " 값이 범위를 벗어났습니다.");
                }
                return value;
            } catch (NumberFormatException exception) {
                throw invalidRecord(record, header + " 값이 숫자가 아닙니다.");
            }
        }
    }
}
