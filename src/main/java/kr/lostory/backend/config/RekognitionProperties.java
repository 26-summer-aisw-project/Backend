package kr.lostory.backend.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.vision.rekognition")
public record RekognitionProperties(
        @NotBlank String region,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Float minConfidence,
        @NotNull @Min(1) @Max(10) Integer maxObjectSuggestions
) {
}
