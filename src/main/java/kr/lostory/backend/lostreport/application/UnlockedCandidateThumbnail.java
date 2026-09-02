package kr.lostory.backend.lostreport.application;

import java.time.Clock;
import kr.lostory.backend.common.storage.ObjectStorage;
import kr.lostory.backend.common.storage.ObjectStorageException;
import kr.lostory.backend.config.ObjectStorageProperties;
import kr.lostory.backend.founditem.domain.FoundItemImageRepository;
import org.springframework.stereotype.Service;

@Service
class UnlockedCandidateThumbnail {

	private final FoundItemImageRepository images;
	private final ObjectStorage storage;
	private final SignedUrlPolicy policy;

	UnlockedCandidateThumbnail(FoundItemImageRepository images, ObjectStorage storage, SignedUrlPolicy policy) {
		this.images = images;
		this.storage = storage;
		this.policy = policy;
	}

	String sign(Long itemId) {
		return images.findByFoundItemIdAndCurrentTrue(itemId).map(image -> {
			String objectKey = image.getObjectKey();
			if (objectKey == null) return null;
			try {
				if (storage.head(objectKey).isEmpty()) return null;
				var expiresAt = policy.expiresAt();
				var signed = storage.presignGet(objectKey, expiresAt);
				return signed.expiresAt().equals(expiresAt) ? signed.url().toString() : null;
			} catch (ObjectStorageException exception) {
				return null;
			}
		}).orElse(null);
	}

	@Service
	static class SignedUrlPolicy {
		private final ObjectStorageProperties properties;
		private final Clock clock;

		SignedUrlPolicy(ObjectStorageProperties properties, Clock clock) {
			this.properties = properties;
			this.clock = clock;
		}

		java.time.Instant expiresAt() {
			return clock.instant().plus(properties.readUrlTtl());
		}
	}
}
