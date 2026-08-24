package kr.lostory.backend.founditem.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kr.lostory.backend.founditem.domain.ItemFeatureKind;

public class VisionFeatureExtractor {

    private static final double MAX_COLOR_DISTANCE = 96.0;

    public Extraction extract(VisionProvider.VisionResult result) {
        validate(result);
        List<LabelScore> labels = normalizeLabels(result.labels());
        String color = dominantColor(result.colors());
        List<ExtractedFeature> features = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            LabelScore label = labels.get(index);
            features.add(new ExtractedFeature(
                    ItemFeatureKind.LABEL,
                    label.label(),
                    (short) (index + 1),
                    BigDecimal.valueOf(label.score()).setScale(3, RoundingMode.HALF_UP)));
        }
        if (color != null) {
            features.add(new ExtractedFeature(ItemFeatureKind.COLOR, color, (short) 1, null));
        }
        return new Extraction(List.copyOf(features));
    }

    public String mapColor(double red, double green, double blue) {
        ColorAnchor closest = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        for (ColorAnchor anchor : ColorAnchor.values()) {
            double distance = Math.sqrt(
                    square(red - anchor.red) + square(green - anchor.green) + square(blue - anchor.blue));
            if (distance < closestDistance) {
                closest = anchor;
                closestDistance = distance;
            }
        }
        return closestDistance <= MAX_COLOR_DISTANCE ? closest.name() : "OTHER";
    }

    private List<LabelScore> normalizeLabels(List<VisionProvider.Label> labels) {
        Map<String, Double> confidenceByLabel = new LinkedHashMap<>();
        for (VisionProvider.Label label : labels) {
            String normalized = Normalizer.normalize(label.description(), Normalizer.Form.NFKC)
                    .trim()
                    .replaceAll("\\s+", " ")
                    .toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                confidenceByLabel.merge(normalized, label.score(), Math::max);
            }
        }
        return confidenceByLabel.entrySet().stream()
                .map(entry -> new LabelScore(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingDouble(LabelScore::score).reversed()
                        .thenComparing(LabelScore::label))
                .limit(5)
                .toList();
    }

    private String dominantColor(List<VisionProvider.Color> colors) {
        return colors.stream()
                .sorted(Comparator.comparingDouble(VisionProvider.Color::pixelFraction).reversed()
                        .thenComparing(Comparator.comparingDouble(VisionProvider.Color::score).reversed())
                        .thenComparingDouble(VisionProvider.Color::red)
                        .thenComparingDouble(VisionProvider.Color::green)
                        .thenComparingDouble(VisionProvider.Color::blue))
                .findFirst()
                .map(color -> mapColor(color.red(), color.green(), color.blue()))
                .orElse(null);
    }

    private void validate(VisionProvider.VisionResult result) {
        for (VisionProvider.Label label : result.labels()) {
            if (label.description() == null || !isUnitInterval(label.score())) {
                throw new VisionExtractionException();
            }
        }
        for (VisionProvider.Color color : result.colors()) {
            if (!isRgb(color.red()) || !isRgb(color.green()) || !isRgb(color.blue())
                    || !isUnitInterval(color.pixelFraction()) || !isUnitInterval(color.score())) {
                throw new VisionExtractionException();
            }
        }
    }

    private boolean isRgb(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 255.0;
    }

    private boolean isUnitInterval(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private double square(double value) {
        return value * value;
    }

    public record Extraction(List<ExtractedFeature> features) {
    }

    public record ExtractedFeature(
            ItemFeatureKind kind,
            String value,
            short ordinal,
            BigDecimal confidence
    ) {
    }

    private record LabelScore(String label, double score) {
    }

    private enum ColorAnchor {
        BLACK(0, 0, 0),
        WHITE(255, 255, 255),
        GRAY(128, 128, 128),
        BROWN(121, 85, 72),
        RED(244, 67, 54),
        ORANGE(255, 152, 0),
        YELLOW(255, 235, 59),
        GREEN(76, 175, 80),
        BLUE(33, 150, 243),
        PURPLE(156, 39, 176),
        PINK(233, 30, 99),
        BEIGE(245, 245, 220),
        SILVER(192, 192, 192),
        GOLD(255, 215, 0);

        private final int red;
        private final int green;
        private final int blue;

        ColorAnchor(int red, int green, int blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }
}
