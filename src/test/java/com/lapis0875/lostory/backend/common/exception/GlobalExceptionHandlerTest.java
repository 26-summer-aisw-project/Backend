package com.lapis0875.lostory.backend.common.exception;

import com.lapis0875.lostory.backend.common.response.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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

}
