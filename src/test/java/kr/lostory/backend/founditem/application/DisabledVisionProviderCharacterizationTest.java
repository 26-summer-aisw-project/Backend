package kr.lostory.backend.founditem.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisabledVisionProviderCharacterizationTest {

    @Test
    void analyze_whenDisabledWithBoundedImageBytes_returnsDeterministicNonAmbiguousFailure() {
        // Given
        VisionProvider provider = new DisabledVisionProvider();
        byte[] boundedImageBytes = {1, 2, 3, 4};
        VisionProvider.VisionRequest request = new VisionProvider.VisionRequest(
                List.of(VisionProvider.FeatureType.LABEL_DETECTION, VisionProvider.FeatureType.IMAGE_PROPERTIES),
                Duration.ofSeconds(2));

        // When / Then
        assertThatThrownBy(() -> provider.analyze(boundedImageBytes, request))
                .isInstanceOfSatisfying(VisionProviderException.class, exception -> {
                    assertThat(exception.isAmbiguous()).isFalse();
                    assertThat(exception).hasMessage("Vision provider request failed.");
                });
    }
}
