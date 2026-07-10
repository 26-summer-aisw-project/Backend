package com.lapis0875.lostory.backend.common;

public enum ErrorCode {

	INVALID_REQUEST("COMMON-001", "The request is invalid."),
	UNAUTHORIZED("COMMON-002", "Authentication is required."),
	FORBIDDEN("COMMON-003", "You do not have permission to access this resource."),
	RESOURCE_NOT_FOUND("COMMON-004", "The requested resource could not be found."),
	INTERNAL_SERVER_ERROR("COMMON-005", "An unexpected server error occurred.");

	private final String code;
	private final String defaultMessage;

	ErrorCode(String code, String defaultMessage) {
		this.code = code;
		this.defaultMessage = defaultMessage;
	}

	public String getCode() {
		return code;
	}

	public String getDefaultMessage() {
		return defaultMessage;
	}

}
