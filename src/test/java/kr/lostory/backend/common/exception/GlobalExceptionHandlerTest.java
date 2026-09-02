package kr.lostory.backend.common.exception;

import java.util.List;
import kr.lostory.backend.common.response.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void handleLostoryExceptionReturnsErrorResponse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        LostoryException exception = new LostoryException(ErrorCode.RESOURCE_NOT_FOUND);

        ResponseEntity<ErrorResponse> response = handler.handleLostoryException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON-004");
        assertThat(response.getBody().message()).isEqualTo("The requested resource could not be found.");
    }

    @Test
    void handleMethodArgumentNotValidExceptionReturnsInvalidRequest() throws NoSuchMethodException {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        TestRequest request = new TestRequest("");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(request, "testRequest");
        bindingResult.addError(new FieldError("testRequest", "title", "must not be blank"));
        MethodParameter methodParameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("testMethod", TestRequest.class),
                0
        );
        MethodArgumentNotValidException exception = new MethodArgumentNotValidException(methodParameter, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValidException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON-001");
        assertThat(response.getBody().message()).isEqualTo("The request is invalid.");
    }

    @Test
    void handleMalformedJsonReturnsInvalidRequest() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException(
                "Malformed JSON",
                new MockHttpInputMessage(new byte[0])
        );

        ResponseEntity<ErrorResponse> response = handler.handleHttpMessageNotReadableException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("COMMON-001");
        assertThat(response.getBody().message()).isEqualTo("The request is invalid.");
    }

    @Test
    void authenticationErrorsHaveStableContracts() {
        assertErrorContract(ErrorCode.DUPLICATE_EMAIL, HttpStatus.CONFLICT, "AUTH-001", "An account with this email already exists.");
        assertErrorContract(ErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "AUTH-002", "Invalid email or password.");
        assertErrorContract(ErrorCode.INVALID_TOKEN, HttpStatus.UNAUTHORIZED, "AUTH-003", "The access token is invalid.");
    }

    @Test
    void visionCapacityErrorHasSafeTooManyRequestsContract() {
        assertErrorContract(ErrorCode.VISION_CAPACITY_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS,
                "VISION-001", "Vision processing capacity is unavailable.");
    }

    @Test
    void methodNotSupported404IsLimitedToRetiredFoundItemPost() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpRequestMethodNotSupportedException exception =
                new HttpRequestMethodNotSupportedException("POST", List.of("GET"));
        MockHttpServletRequest retiredFoundItemPost = new MockHttpServletRequest(
                "POST", "/api/v1/found-items");
        MockHttpServletRequest unrelatedImagePost = new MockHttpServletRequest(
                "POST", "/api/v1/found-items/42/image");

        ResponseEntity<ErrorResponse> retired = handler.handleMethodNotSupported(exception, retiredFoundItemPost);
        ResponseEntity<ErrorResponse> unrelated = handler.handleMethodNotSupported(exception, unrelatedImagePost);

        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(retired.getBody().code()).isEqualTo("COMMON-004");
        assertThat(unrelated.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(unrelated.getBody().code()).isEqualTo("COMMON-001");
    }

    private void assertErrorContract(ErrorCode errorCode, HttpStatus status, String code, String message) {
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler()
                .handleLostoryException(new LostoryException(errorCode));

        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().message()).isEqualTo(message);
    }

    private void testMethod(TestRequest request) {
    }

    private record TestRequest(String title) {
    }

}
