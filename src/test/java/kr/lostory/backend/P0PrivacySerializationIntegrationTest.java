package kr.lostory.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import kr.lostory.backend.founditem.presentation.FoundItemSignedUrlResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class P0PrivacySerializationIntegrationTest {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void candidateSerializationContainsOnlyPublicScoreFields() throws Exception {
		CandidateResponse response = new CandidateResponse(
				Instant.parse("2026-08-25T00:00:00Z"), false,
				List.of(new Candidate("810", (short) 1, new BigDecimal("82.40"))));

		JsonNode body = json.readTree(json.writeValueAsString(response));

		assertThat(body.propertyNames()).containsExactlyInAnyOrder("lastMatchedAt", "candidatesStale", "data");
		assertThat(body.get("data").get(0).propertyNames())
				.containsExactlyInAnyOrder("candidateId", "rank", "score");
		assertThat(body.toString()).doesNotContain(
				"objectKey", "storageKey", "mediaKey", "rawLabel", "confidence", "finderId",
				"latitude", "longitude", "storageDescription", "accessKey", "secretKey");
	}

	@Test
	void signedImageSerializationContainsOnlyAuthorizedResponseFields() throws Exception {
		FoundItemSignedUrlResponse response = new FoundItemSignedUrlResponse(
			URI.create("https://signed.example.test/private-image"),
			Instant.parse("2026-08-27T00:05:00Z"));

		JsonNode body = json.readTree(json.writeValueAsString(response));

		assertThat(body.propertyNames()).containsExactlyInAnyOrder("url", "expiresAt");
		assertThat(body.toString()).doesNotContain(
			"objectKey", "storagePath", "storedFilename", "originalFilename", "contentType", "sizeBytes");
	}

	private record CandidateResponse(Instant lastMatchedAt, boolean candidatesStale, List<Candidate> data) {
	}

	private record Candidate(String candidateId, short rank, BigDecimal score) {
	}
}
