package kr.lostory.backend.founditem.application;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.DeadlineExceededException;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.BatchAnnotateImagesResponse;
import com.google.cloud.vision.v1.ColorInfo;
import com.google.cloud.vision.v1.EntityAnnotation;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.protobuf.ByteString;
import java.util.List;

public class GoogleCloudVisionProvider implements VisionProvider {

    private final ImageAnnotatorClient client;

    public GoogleCloudVisionProvider(ImageAnnotatorClient client) {
        this.client = client;
    }

    @Override
    public VisionResult analyze(byte[] imageBytes, VisionRequest request) {
        if (!request.features().equals(List.of(FeatureType.LABEL_DETECTION, FeatureType.IMAGE_PROPERTIES))) {
            throw new IllegalArgumentException("Exactly LABEL_DETECTION and IMAGE_PROPERTIES are required.");
        }
        AnnotateImageRequest annotateRequest = AnnotateImageRequest.newBuilder()
                .setImage(Image.newBuilder().setContent(ByteString.copyFrom(imageBytes)))
                .addFeatures(feature(Feature.Type.LABEL_DETECTION))
                .addFeatures(feature(Feature.Type.IMAGE_PROPERTIES))
                .build();
        try {
            BatchAnnotateImagesResponse batch = client.batchAnnotateImages(List.of(annotateRequest));
            AnnotateImageResponse response = batch.getResponses(0);
            if (response.hasError()) {
                throw new VisionProviderException(false);
            }
            return new VisionResult(
                    response.getLabelAnnotationsList().stream().map(this::label).toList(),
                    response.getImagePropertiesAnnotation().getDominantColors().getColorsList().stream()
                            .map(this::color).toList());
        } catch (DeadlineExceededException exception) {
            throw new VisionProviderException(true);
        } catch (ApiException exception) {
            throw new VisionProviderException(false);
        }
    }

    private Feature feature(Feature.Type type) {
        return Feature.newBuilder().setType(type).build();
    }

    private Label label(EntityAnnotation annotation) {
        return new Label(annotation.getDescription(), annotation.getScore());
    }

    private Color color(ColorInfo color) {
        return new Color(
                color.getColor().getRed(),
                color.getColor().getGreen(),
                color.getColor().getBlue(),
                color.getPixelFraction(),
                color.getScore());
    }
}
