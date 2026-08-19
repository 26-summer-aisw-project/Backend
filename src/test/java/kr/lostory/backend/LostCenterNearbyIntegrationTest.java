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
        assertThat(responses.get(0).centerKey()).isEqualTo("ssu_primary_student_service_team");
        assertThat(responses.get(0).distanceMeters()).isLessThan(responses.get(1).distanceMeters());
        assertThat(responses.get(1).distanceMeters()).isLessThan(responses.get(2).distanceMeters());
        assertThat(responses).allSatisfy(response -> {
            assertThat(response.handoffAvailable()).isEqualTo("yes");
            assertThat(response.verificationStatus()).isIn(
                    "official_verified",
                    "official_board_verified",
                    "official_local_verified"
            );
        });
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

    private static String uniqueEmail() {
        return "lost-center-user-" + UUID.randomUUID() + "@example.test";
    }
}
