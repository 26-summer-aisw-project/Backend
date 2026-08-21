package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import kr.lostory.backend.lostcenter.application.LostCenterCsvInitializer;
import kr.lostory.backend.lostcenter.domain.LostCenter;
import kr.lostory.backend.lostcenter.domain.LostCenterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@Import(PostgresTestContainerConfig.class)
@SpringBootTest
class LostCenterCsvInitializationIntegrationTest {

    @Autowired
    private LostCenterCsvInitializer lostCenterCsvInitializer;

    @Autowired
    private LostCenterRepository lostCenterRepository;

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
    void reloadDeletesCsvManagedCentersRemovedOrWithdrawnFromReviewedData() {
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

        lostCenterCsvInitializer.run(new DefaultApplicationArguments());

        assertThat(lostCenterRepository.findAllBySourceKeyIn(List.of(
                removedCenter.getSourceKey(),
                withdrawnCenter.getSourceKey(),
                manualCenter.getSourceKey()
        )))
                .extracting(LostCenter::getSourceKey)
                .containsExactly(manualCenter.getSourceKey());
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
