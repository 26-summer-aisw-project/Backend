package kr.lostory.backend.common.exception;

import java.util.Objects;

public class LostoryException extends RuntimeException {

	private final ErrorCode errorCode;

	public LostoryException(ErrorCode errorCode) {
		this(Objects.requireNonNull(errorCode, "errorCode must not be null"), errorCode.getDefaultMessage());
	}

	public LostoryException(ErrorCode errorCode, String message) {
		super(Objects.requireNonNull(message, "message must not be null"));
		this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

}
