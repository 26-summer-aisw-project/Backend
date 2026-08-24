package kr.lostory.backend.common.exception;

import kr.lostory.backend.common.response.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LostoryException.class)
    public ResponseEntity<ErrorResponse> handleLostoryException(LostoryException exception) {
        ErrorResponse response = ErrorResponse.from(exception);
        return ResponseEntity
                .status(resolveStatus(exception.getErrorCode()))
                .body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        ErrorResponse response = new ErrorResponse(ErrorCode.INVALID_REQUEST);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleInvalidBoundaryException(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse(ErrorCode.INVALID_REQUEST));
    }

    private HttpStatus resolveStatus(ErrorCode errorCode) {
        return switch (errorCode) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RESOURCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INTERNAL_SERVER_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            case MEDIA_NOT_AVAILABLE -> HttpStatus.GONE;
            case DUPLICATE_EMAIL -> HttpStatus.CONFLICT;
            case INVALID_CREDENTIALS, INVALID_TOKEN -> HttpStatus.UNAUTHORIZED;
        };
    }

}
