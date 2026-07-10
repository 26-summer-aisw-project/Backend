package com.lapis0875.lostory.backend.common;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

public record ErrorResponse(
	String code,
	String message,
	List<FieldErrorDetail> fieldErrors,
	Instant timestamp
) {

	public ErrorResponse {
		Objects.requireNonNull(code, "code must not be null");
		Objects.requireNonNull(message, "message must not be null");
		fieldErrors = List.copyOf(Objects.requireNonNull(fieldErrors, "fieldErrors must not be null"));
		Objects.requireNonNull(timestamp, "timestamp must not be null");
	}

	public ErrorResponse(ErrorCode errorCode) {
		this(errorCode, Objects.requireNonNull(errorCode, "errorCode must not be null").getDefaultMessage());
	}

	public ErrorResponse(ErrorCode errorCode, String message) {
		this(errorCode, message, List.of());
	}

	public ErrorResponse(ErrorCode errorCode, List<FieldError> fieldErrors) {
		this(errorCode, Objects.requireNonNull(errorCode, "errorCode must not be null").getDefaultMessage(), fieldErrors);
	}

	public ErrorResponse(ErrorCode errorCode, BindingResult bindingResult) {
		this(errorCode, Objects.requireNonNull(bindingResult, "bindingResult must not be null").getFieldErrors());
	}

	public ErrorResponse(ErrorCode errorCode, String message, List<FieldError> fieldErrors) {
		this(
			Objects.requireNonNull(errorCode, "errorCode must not be null").name(),
			message,
			Objects.requireNonNull(fieldErrors, "fieldErrors must not be null").stream()
				.map(FieldErrorDetail::from)
				.toList(),
			Instant.now()
		);
	}

	public static ErrorResponse from(LostoryException exception) {
		Objects.requireNonNull(exception, "exception must not be null");
		return new ErrorResponse(exception.getErrorCode(), exception.getMessage());
	}

	public record FieldErrorDetail(String field, String message) {

		private static FieldErrorDetail from(FieldError error) {
			Objects.requireNonNull(error, "error must not be null");
			return new FieldErrorDetail(error.getField(), error.getDefaultMessage());
		}

	}

}
