package kr.lostory.backend.founditem.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "vision.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledVisionProvider implements VisionProvider {

    @Override
    public VisionResult analyze(byte[] imageBytes, VisionRequest request) {
        throw new VisionProviderException(false);
    }
}
