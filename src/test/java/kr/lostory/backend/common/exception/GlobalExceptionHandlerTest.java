package kr.lostory.backend.common.exception;

import kr.lostory.backend.common.response.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
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
        assertThat(response.getBody().fieldErrors()).isEmpty();
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    void handleMethodArgumentNotValidExceptionReturnsFieldErrors() throws NoSuchMethodException {
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
        assertThat(response.getBody().fieldErrors()).hasSize(1);
        assertThat(response.getBody().fieldErrors().getFirst().field()).isEqualTo("title");
        assertThat(response.getBody().fieldErrors().getFirst().message()).isEqualTo("must not be blank");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    private void testMethod(TestRequest request) {
    }

    private record TestRequest(String title) {
    }

}
