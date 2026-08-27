package kr.lostory.backend.founditem.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsFeatureName;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsImageProperties;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.DominantColor;
import software.amazon.awssdk.services.rekognition.model.Label;

class RekognitionVisionProviderContractTest {

    @Test
    void analyze_whenWorkerSuppliesBoundedImageBytes_forwardsExactBytesAndMapsNeutralResponse() {
        // Given
        AtomicReference<DetectLabelsRequest> capturedRequest = new AtomicReference<>();
        RekognitionClient client = clientReturning(capturedRequest, successfulResponse());
        VisionProvider provider = provider(client);
        byte[] boundedImageBytes = {0x01, 0x23, 0x45, (byte) 0xfe};

        // When
        VisionProvider.VisionResult result = provider.analyze(boundedImageBytes, supportedRequest());

        // Then
        DetectLabelsRequest request = capturedRequest.get();
        assertThat(request.image().bytes().asByteArray()).containsExactly(boundedImageBytes);
        assertThat(request.image().s3Object()).isNull();
        assertThat(request.features()).containsExactly(
                DetectLabelsFeatureName.GENERAL_LABELS,
                DetectLabelsFeatureName.IMAGE_PROPERTIES);
        assertThat(result).isEqualTo(new VisionProvider.VisionResult(
                List.of(new VisionProvider.Label("neutral-label", 0.8)),
                List.of(new VisionProvider.Color(12, 34, 56, 0.25, 0.0))));
    }

    @Test
    void analyze_whenImageBytesAreEmpty_rejectsMalformedInputWithoutProviderCall() {
        // Given
        AtomicInteger providerCalls = new AtomicInteger();
        VisionProvider provider = provider(clientCounting(providerCalls));

        // When / Then
        assertThatThrownBy(() -> provider.analyze(new byte[0], supportedRequest()))
                .isInstanceOfSatisfying(VisionProviderException.class, exception -> {
                    assertThat(exception.isAmbiguous()).isFalse();
                    assertThat(exception).hasMessage("Vision provider request failed.");
                    assertThat(exception.getCause()).isNull();
                });
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void analyze_whenRekognitionTimesOut_usesAmbiguousNonLeakingProviderFailure() {
        // Given
        ApiCallTimeoutException timeout = ApiCallTimeoutException.create(2000L);
        assertThat(timeout).isExactlyInstanceOf(ApiCallTimeoutException.class);
        VisionProvider provider = provider(clientThrowing(timeout));

        // When / Then
        assertThatThrownBy(() -> provider.analyze(boundedImageBytes(), supportedRequest()))
                .isInstanceOfSatisfying(VisionProviderException.class, exception -> {
                    assertThat(exception.isAmbiguous()).isTrue();
                    assertThat(exception).hasMessage("Vision provider request failed.");
                    assertThat(exception.getCause()).isNull();
                });
    }

    @Test
    void analyze_whenRekognitionClientFails_usesNonAmbiguousNonLeakingProviderFailure() {
        // Given
        VisionProvider provider = provider(clientThrowing(SdkClientException.create("redacted failure")));

        // When / Then
        assertThatThrownBy(() -> provider.analyze(boundedImageBytes(), supportedRequest()))
                .isInstanceOfSatisfying(VisionProviderException.class, exception -> {
                    assertThat(exception.isAmbiguous()).isFalse();
                    assertThat(exception).hasMessage("Vision provider request failed.");
                    assertThat(exception.getCause()).isNull();
                });
    }

    @Test
    void analyze_whenRekognitionClientFailsWithTransportCause_usesAmbiguousNonLeakingProviderFailure() {
        // Given
        IOException transportFailure = new IOException("transport diagnostic");
        SdkClientException transportFailureWrapper = SdkClientException.create(
                "sdk wrapper diagnostic", transportFailure);
        assertThat(transportFailureWrapper).isExactlyInstanceOf(SdkClientException.class);
        assertThat(transportFailureWrapper.getCause()).isSameAs(transportFailure);
        VisionProvider provider = provider(clientThrowing(transportFailureWrapper));

        // When / Then
        assertThatThrownBy(() -> provider.analyze(boundedImageBytes(), supportedRequest()))
                .isInstanceOfSatisfying(VisionProviderException.class, exception -> {
                    assertThat(exception.isAmbiguous()).isTrue();
                    assertThat(exception).hasMessage("Vision provider request failed.");
                    assertThat(exception.getCause()).isNull();
                });
    }

    private VisionProvider provider(RekognitionClient client) {
        try {
            Class<?> providerClass = Class.forName(
                    "kr.lostory.backend.founditem.application.RekognitionVisionProvider");
            return VisionProvider.class.cast(providerClass.getConstructor(RekognitionClient.class).newInstance(client));
        } catch (ClassNotFoundException exception) {
            throw new AssertionError("RekognitionVisionProvider adapter class is absent.", exception);
        } catch (NoSuchMethodException exception) {
            throw new AssertionError("RekognitionVisionProvider must accept RekognitionClient.", exception);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException exception) {
            throw new AssertionError("RekognitionVisionProvider could not be constructed.", exception);
        }
    }

    private RekognitionClient clientReturning(
            AtomicReference<DetectLabelsRequest> capturedRequest, DetectLabelsResponse response) {
        return client((request) -> {
            capturedRequest.set(request);
            return response;
        });
    }

    private RekognitionClient clientCounting(AtomicInteger providerCalls) {
        return client((request) -> {
            providerCalls.incrementAndGet();
            return successfulResponse();
        });
    }

    private RekognitionClient clientThrowing(RuntimeException failure) {
        return client((request) -> {
            throw failure;
        });
    }

    private RekognitionClient client(DetectLabelsCall call) {
        return (RekognitionClient) Proxy.newProxyInstance(
                RekognitionClient.class.getClassLoader(),
                new Class<?>[] {RekognitionClient.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("detectLabels")
                            && arguments != null
                            && arguments.length == 1
                            && arguments[0] instanceof DetectLabelsRequest request) {
                        return call.execute(request);
                    }
                    if (method.getName().equals("serviceName")) {
                        return "Rekognition";
                    }
                    if (method.getName().equals("close")) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private DetectLabelsResponse successfulResponse() {
        return DetectLabelsResponse.builder()
                .labels(Label.builder().name("neutral-label").confidence(80.0f).build())
                .imageProperties(DetectLabelsImageProperties.builder()
                        .dominantColors(DominantColor.builder()
                                .red(12)
                                .green(34)
                                .blue(56)
                                .pixelPercent(25.0f)
                                .build())
                        .build())
                .build();
    }

    private byte[] boundedImageBytes() {
        return new byte[] {0x01, 0x23, 0x45, (byte) 0xfe};
    }

    private VisionProvider.VisionRequest supportedRequest() {
        return new VisionProvider.VisionRequest(
                List.of(VisionProvider.FeatureType.LABEL_DETECTION, VisionProvider.FeatureType.IMAGE_PROPERTIES),
                Duration.ofSeconds(2));
    }

    @FunctionalInterface
    private interface DetectLabelsCall {
        DetectLabelsResponse execute(DetectLabelsRequest request);
    }
}
