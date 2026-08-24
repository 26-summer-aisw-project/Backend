package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import kr.lostory.backend.lostcenter.application.LostCenterCsvInitializer;
import kr.lostory.backend.lostcenter.domain.LostCenter;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import kr.lostory.backend.user.domain.User;
import kr.lostory.backend.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest
@Transactional
class LostCenterCsvInitializationIntegrationTest {

    @Autowired
    private LostCenterCsvInitializer lostCenterCsvInitializer;

    @Autowired
    private LostCenterRepository lostCenterRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void curatedCsvIsLoadedAtStartupAndCanBeSafelyReloaded() {
        assertThat(lostCenterRepository.count()).isEqualTo(24);

        LostCenter center = lostCenterRepository.findAllBySourceKeyIn(
                        List.of("ssu_primary_student_service_team")
                )
                .getFirst();
        assertThat(center.getLocation().getSRID()).isEqualTo(4326);
        assertThat(center.getLocation().getY()).isEqualTo(37.49675193536618);
        assertThat(center.getLocation().getX()).isEqualTo(126.95697691809838);
        assertThat(center.getVerificationStatus()).isEqualTo("official_verified");

        lostCenterCsvInitializer.run(new DefaultApplicationArguments());

        assertThat(lostCenterRepository.count()).isEqualTo(24);
    }

    @Test
    void reloadDeactivatesCsvManagedCentersRemovedOrWithdrawnFromReviewedData() {
        LostCenter removedCenter = lostCenterRepository.saveAndFlush(csvManagedCenter("removed-center"));
        LostCenter withdrawnCenter = lostCenterRepository.saveAndFlush(csvManagedCenter(
                "konkuk_lost_center_candidate"
        ));
        LostCenter manualCenter = lostCenterRepository.saveAndFlush(new LostCenter(
                "directory:manual-center",
                "수동 분실물센터",
                "서울특별시 동작구 상도로 369",
                removedCenter.getLocation(),
                "02-820-0000",
                "평일 09:00-18:00"
        ));
        User finder = userRepository.saveAndFlush(new User(UUID.randomUUID() + "@example.test", "hash"));
        jdbc.update("""
                INSERT INTO found_items (finder_id, name, category, description, found_at, storage_method,
                    center_id, handover_status, status, vision_status, analysis_generation,
                    created_at, updated_at, expired_at)
                VALUES (?, 'referenced center fixture', 'OTHER', 'fixture', now(), 'HANDED_TO_CENTER',
                    ?, 'NONE', 'PENDING_HANDOVER', 'FAILED', 0, now(), now(), now() + interval '14 days')
                """, finder.getId(), removedCenter.getId());

        lostCenterCsvInitializer.run(new DefaultApplicationArguments());

        assertThat(lostCenterRepository.findAllBySourceKeyIn(List.of(
                removedCenter.getSourceKey(),
                withdrawnCenter.getSourceKey(),
                manualCenter.getSourceKey()
        )))
                .filteredOn(LostCenter::isActive)
                .extracting(LostCenter::getSourceKey)
                .containsExactly(manualCenter.getSourceKey());
        assertThat(lostCenterRepository.findAllBySourceKeyIn(List.of(
                removedCenter.getSourceKey(), withdrawnCenter.getSourceKey())))
                .allSatisfy(center -> assertThat(center.isActive()).isFalse());
        assertThat(manualCenter.isCsvManaged()).isFalse();
    }

    private static LostCenter csvManagedCenter(String centerKey) {
        return new LostCenter(
                centerKey,
                "검수 철회 테스트 센터",
                "테스트 대학교",
                "서울특별시 동작구 상도로 369",
                null,
                new BigDecimal("37.49675193536618"),
                new BigDecimal("126.95697691809838"),
                "02-820-0000",
                "평일 09:00-18:00",
                "official_verified"
        );
    }
}
