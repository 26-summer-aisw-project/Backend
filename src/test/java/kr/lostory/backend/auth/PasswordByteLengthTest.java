package kr.lostory.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PasswordByteLengthTest {

	private final PasswordByteLength.Validator validator = new PasswordByteLength.Validator();

	@Test
	void validatesInclusiveUtf8ByteBoundariesIncludingMultibytePasswords() {
		// Given
		String multibyteAtMaximum = "가".repeat(24);
		String multibyteOverMaximum = "가".repeat(25);

		// When
		boolean sevenAsciiBytesAccepted = validator.isValid("a".repeat(7), null);
		boolean eightAsciiBytesAccepted = validator.isValid("a".repeat(8), null);
		boolean seventyTwoAsciiBytesAccepted = validator.isValid("a".repeat(72), null);
		boolean seventyThreeAsciiBytesAccepted = validator.isValid("a".repeat(73), null);
		boolean multibyteMaximumAccepted = validator.isValid(multibyteAtMaximum, null);
		boolean multibyteOverMaximumAccepted = validator.isValid(multibyteOverMaximum, null);
		boolean nullAccepted = validator.isValid(null, null);

		// Then
		assertThat(sevenAsciiBytesAccepted).isFalse();
		assertThat(eightAsciiBytesAccepted).isTrue();
		assertThat(seventyTwoAsciiBytesAccepted).isTrue();
		assertThat(seventyThreeAsciiBytesAccepted).isFalse();
		assertThat(multibyteAtMaximum).hasSize(24);
		assertThat(multibyteAtMaximum.getBytes(StandardCharsets.UTF_8)).hasSize(72);
		assertThat(multibyteMaximumAccepted).isTrue();
		assertThat(multibyteOverMaximumAccepted).isFalse();
		assertThat(nullAccepted).isTrue();
	}
}
