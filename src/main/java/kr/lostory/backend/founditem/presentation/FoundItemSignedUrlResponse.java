package kr.lostory.backend.founditem.presentation;

import java.net.URI;
import java.time.Instant;

public record FoundItemSignedUrlResponse(URI url, Instant expiresAt) {
}
