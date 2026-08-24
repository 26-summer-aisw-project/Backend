package kr.lostory.backend.common.exception;

public enum ErrorCode {

	INVALID_REQUEST("COMMON-001", "The request is invalid."),
	UNAUTHORIZED("COMMON-002", "Authentication is required."),
	FORBIDDEN("COMMON-003", "You do not have permission to access this resource."),
	RESOURCE_NOT_FOUND("COMMON-004", "The requested resource could not be found."),
	INTERNAL_SERVER_ERROR("COMMON-005", "An unexpected server error occurred."),
	MEDIA_NOT_AVAILABLE("MEDIA_NOT_AVAILABLE", "Media is no longer available."),
	VISION_CAPACITY_EXCEEDED("VISION-001", "Vision processing capacity is unavailable."),
	REPORT_NOT_OPEN("REPORT_NOT_OPEN", "The lost report is not open."),
	DUPLICATE_EMAIL("AUTH-001", "An account with this email already exists."),
	INVALID_CREDENTIALS("AUTH-002", "Invalid email or password."),
	INVALID_TOKEN("AUTH-003", "The access token is invalid.");

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
