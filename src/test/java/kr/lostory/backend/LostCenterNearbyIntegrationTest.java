package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;
import kr.lostory.backend.founditem.domain.FoundItem;
import kr.lostory.backend.founditem.domain.FoundItemRepository;
import kr.lostory.backend.founditem.domain.StorageMethod;
import kr.lostory.backend.lostcenter.application.LostCenterService;
import kr.lostory.backend.lostcenter.presentation.NearbyLostCenterResponse;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest
@Transactional
class LostCenterNearbyIntegrationTest {

    private static final String HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoO5n33S5U9P4XQxG1VVDzI7kVxwZKXgOe";

    @Autowired
    private LostCenterService lostCenterService;

    @Autowired
    private FoundItemRepository foundItemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void nearbyLostCentersAreReturnedByDistanceFromFoundItem() {
        User finder = userRepository.saveAndFlush(new User(uniqueEmail(), HASH));

        FoundItem foundItem = foundItemRepository.saveAndFlush(new FoundItem(
                finder.getId(),
                "Black card wallet",
                "WALLET_CARD",
                "Black leather card wallet with several cards",
                Instant.parse("2026-08-04T09:30:00Z"),
                new BigDecimal("37.4961234"),
                new BigDecimal("126.9575432"),
                "서울특별시 동작구 상도로 369",
                "학생회관 2층",
                StorageMethod.HANDED_TO_CENTER,
                null,
                "Student center information desk"
        ));

        insertLostCenter(
                "near_center",
                "가까운 분실물센터",
                "숭실대학교",
                "02-0000-0001",
                "서울 동작구 상도로 369",
                "학생회관 2층",
                "09:00~18:00",
                "126.9576000",
                "37.4962000"
        );
        insertLostCenter(
                "middle_center",
                "중간 분실물센터",
                "숭실대학교",
                "02-0000-0002",
                "서울 동작구 상도로 369",
                "중앙도서관 1층",
                "09:00~18:00",
                "126.9600000",
                "37.4980000"
        );
        insertLostCenter(
                "far_center",
                "먼 분실물센터",
                "숭실대학교",
                "02-0000-0003",
                "서울 동작구 상도로 369",
                "정문 안내소",
                "09:00~18:00",
                "126.9800000",
                "37.5200000"
        );

        List<NearbyLostCenterResponse> responses = lostCenterService.findNearbyByFoundItem(
                foundItem.getId(),
                finder.getId()
        );

        Boolean lostCenterMigrationApplied = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM flyway_schema_history WHERE success AND version = '6')",
                Boolean.class
        );

        assertThat(lostCenterMigrationApplied).isTrue();
        assertThat(responses).hasSize(3);
        assertThat(responses)
                .extracting(NearbyLostCenterResponse::name)
                .containsExactly("가까운 분실물센터", "중간 분실물센터", "먼 분실물센터");
        assertThat(responses.get(0).distanceMeters()).isLessThan(responses.get(1).distanceMeters());
        assertThat(responses.get(1).distanceMeters()).isLessThan(responses.get(2).distanceMeters());
        assertThat(responses.get(0).handoffAvailable()).isEqualTo("yes");
        assertThat(responses.get(0).verificationStatus()).isEqualTo("official_verified");
    }

    @Test
    void requesterCannotGetNearbyLostCentersForOthersFoundItem() {
        User finder = userRepository.saveAndFlush(new User(uniqueEmail(), HASH));
        User otherUser = userRepository.saveAndFlush(new User(uniqueEmail(), HASH));

        FoundItem foundItem = foundItemRepository.saveAndFlush(new FoundItem(
                finder.getId(),
                "Black card wallet",
                "WALLET_CARD",
                "Black leather card wallet with several cards",
                Instant.parse("2026-08-04T09:30:00Z"),
                new BigDecimal("37.4961234"),
                new BigDecimal("126.9575432"),
                "서울특별시 동작구 상도로 369",
                "학생회관 2층",
                StorageMethod.HANDED_TO_CENTER,
                null,
                "Student center information desk"
        ));

        assertThatThrownBy(() -> lostCenterService.findNearbyByFoundItem(
                foundItem.getId(),
                otherUser.getId()
        ))
                .isInstanceOf(LostoryException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private void insertLostCenter(
            String centerKey,
            String name,
            String parentPlace,
            String phoneNumber,
            String address,
            String detailLocation,
            String operatingHours,
            String longitude,
            String latitude
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO lost_centers (
                    center_key,
                    name,
                    parent_place,
                    phone_number,
                    address,
                    detail_location,
                    location,
                    operating_hours,
                    handoff_available,
                    verification_status
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?,
                    'yes',
                    'official_verified'
                )
                """,
                centerKey,
                name,
                parentPlace,
                phoneNumber,
                address,
                detailLocation,
                new BigDecimal(longitude),
                new BigDecimal(latitude),
                operatingHours
        );
    }

    private static String uniqueEmail() {
        return "lost-center-user-" + UUID.randomUUID() + "@example.test";
    }
}