package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(lostCenterRepository.count()).isEqualTo(48);

        LostCenter center = lostCenterRepository.findAllByCenterKeyIn(
                        List.of("ssu_primary_student_service_team")
                )
                .getFirst();
        assertThat(center.getLocation().getSRID()).isEqualTo(4326);
        assertThat(center.getLocation().getY()).isEqualTo(37.49675193536618);
        assertThat(center.getLocation().getX()).isEqualTo(126.95697691809838);

        lostCenterCsvInitializer.run(new DefaultApplicationArguments());

        assertThat(lostCenterRepository.count()).isEqualTo(48);
    }
}
