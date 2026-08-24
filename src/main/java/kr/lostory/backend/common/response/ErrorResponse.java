package kr.lostory.backend.common.response;

import java.util.Objects;

import kr.lostory.backend.common.exception.ErrorCode;
import kr.lostory.backend.common.exception.LostoryException;

public record ErrorResponse(String code, String message) {

	public ErrorResponse {
		Objects.requireNonNull(code, "code must not be null");
		Objects.requireNonNull(message, "message must not be null");
	}

	public ErrorResponse(ErrorCode errorCode) {
		this(errorCode, Objects.requireNonNull(errorCode, "errorCode must not be null").getDefaultMessage());
	}

	public ErrorResponse(ErrorCode errorCode, String message) {
		this(Objects.requireNonNull(errorCode, "errorCode must not be null").getCode(), message);
	}

	public static ErrorResponse from(LostoryException exception) {
		Objects.requireNonNull(exception, "exception must not be null");
		return new ErrorResponse(exception.getErrorCode(), exception.getMessage());
	}
}
