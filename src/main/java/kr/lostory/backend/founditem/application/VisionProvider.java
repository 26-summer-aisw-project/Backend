package kr.lostory.backend.founditem.application;

import java.time.Duration;
import java.util.List;

public interface VisionProvider {

    VisionResult analyze(byte[] imageBytes, VisionRequest request);

    enum FeatureType {
        LABEL_DETECTION,
        IMAGE_PROPERTIES
    }

    record VisionRequest(List<FeatureType> features, Duration deadline) {
        public VisionRequest {
            features = List.copyOf(features);
        }
    }

    record VisionResult(List<Label> labels, List<Color> colors) {
        public VisionResult {
            labels = List.copyOf(labels);
            colors = List.copyOf(colors);
        }
    }

    record Label(String description, double score) {
    }

    record Color(double red, double green, double blue, double pixelFraction, double score) {
    }
}
