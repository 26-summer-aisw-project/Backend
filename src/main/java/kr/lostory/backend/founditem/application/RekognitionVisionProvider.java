package kr.lostory.backend.founditem.application;

import java.io.IOException;
import java.util.List;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsFeatureName;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsRequest;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.DominantColor;

public class RekognitionVisionProvider implements VisionProvider {

    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    private static final float DEFAULT_MIN_CONFIDENCE = 70.0f;
    private static final int DEFAULT_MAX_LABELS = 10;

    private final RekognitionClient client;
    private final float minConfidence;
    private final int maxLabels;

    public RekognitionVisionProvider(RekognitionClient client) {
        this(client, DEFAULT_MIN_CONFIDENCE, DEFAULT_MAX_LABELS);
    }

    public RekognitionVisionProvider(RekognitionClient client, float minConfidence, int maxLabels) {
        this.client = client;
        this.minConfidence = minConfidence;
        this.maxLabels = maxLabels;
    }

    @Override
    public VisionResult analyze(byte[] imageBytes, VisionRequest request) {
        if (imageBytes == null || imageBytes.length == 0 || imageBytes.length > MAX_IMAGE_BYTES) {
            throw new VisionProviderException(false);
        }
        if (!request.features().equals(List.of(FeatureType.LABEL_DETECTION, FeatureType.IMAGE_PROPERTIES))) {
            throw new IllegalArgumentException("Exactly LABEL_DETECTION and IMAGE_PROPERTIES are required.");
        }

        DetectLabelsRequest providerRequest = DetectLabelsRequest.builder()
                .image(image -> image.bytes(SdkBytes.fromByteArray(imageBytes)))
                .features(
                        DetectLabelsFeatureName.GENERAL_LABELS,
                        DetectLabelsFeatureName.IMAGE_PROPERTIES)
                .minConfidence(minConfidence)
                .maxLabels(maxLabels)
                .overrideConfiguration(configuration -> configuration
                        .apiCallTimeout(request.deadline())
                        .apiCallAttemptTimeout(request.deadline()))
                .build();
        try {
            DetectLabelsResponse response = client.detectLabels(providerRequest);
            List<Label> labels = response.labels().stream()
                    .map(label -> new Label(label.name(), normalizedPercent(label.confidence())))
                    .toList();
            List<Color> colors = response.imageProperties() == null
                    ? List.of()
                    : response.imageProperties().dominantColors().stream().map(this::color).toList();
            return new VisionResult(labels, colors);
        } catch (ApiCallTimeoutException | ApiCallAttemptTimeoutException exception) {
            throw new VisionProviderException(true);
        } catch (SdkClientException exception) {
            throw new VisionProviderException(exception.getCause() instanceof IOException);
        } catch (SdkServiceException exception) {
            throw new VisionProviderException(false);
        }
    }

    private Color color(DominantColor color) {
        return new Color(
                color.red(),
                color.green(),
                color.blue(),
                normalizedPercent(color.pixelPercent()),
                0.0);
    }

    private double normalizedPercent(Number percent) {
        return percent == null ? 0.0 : percent.doubleValue() / 100.0;
    }
}
