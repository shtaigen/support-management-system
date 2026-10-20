package io.github.shtaigen.support.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final WebRequest request =
            new ServletWebRequest(new MockHttpServletRequest(), new MockHttpServletResponse());

    @Test
    void accessDeniedIsLeftToSpringSecurity() {
        AccessDeniedException ex = new AccessDeniedException("denied");

        assertThat(catchThrowable(() -> handler.handleUnexpected(ex, request))).isSameAs(ex);
    }

    @Test
    void authenticationFailureIsLeftToSpringSecurity() {
        BadCredentialsException ex = new BadCredentialsException("bad");

        assertThat(catchThrowable(() -> handler.handleUnexpected(ex, request))).isSameAs(ex);
    }

    @Test
    void otherExceptionsBecomeGenericInternalError() {
        ResponseEntity<Object> response = handler.handleUnexpected(new IllegalStateException("secret detail"), request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getDetail()).isEqualTo("Internal server error.");
        assertThat(problem.getProperties()).isNull();
        assertThat(problem.toString()).doesNotContain("secret detail");
    }
}
