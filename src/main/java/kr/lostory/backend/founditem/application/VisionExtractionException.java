package kr.lostory.backend.founditem.application;

final class VisionExtractionException extends RuntimeException {

    VisionExtractionException() {
        super("Vision provider returned malformed numeric data.");
    }
}
