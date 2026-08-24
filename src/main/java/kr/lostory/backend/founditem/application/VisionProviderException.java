package kr.lostory.backend.founditem.application;

public class VisionProviderException extends RuntimeException {

    private final boolean ambiguous;

    public VisionProviderException(boolean ambiguous) {
        super("Vision provider request failed.");
        this.ambiguous = ambiguous;
    }

    public boolean isAmbiguous() {
        return ambiguous;
    }
}
