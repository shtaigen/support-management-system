package io.github.shtaigen.support.common;

import io.github.shtaigen.support.ticket.InvalidTicketTransitionException;
import io.github.shtaigen.support.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
    void optimisticLockingFailureBecomesConflictWithFixedDetail() {
        ObjectOptimisticLockingFailureException ex =
                new ObjectOptimisticLockingFailureException("SecretEntityMarker", 4711L);

        ResponseEntity<Object> response = handler.handleOptimisticLockingFailure(ex, request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getDetail()).isEqualTo("The data was changed by another request. Reload it and try again.");
        assertThat(problem.getProperties()).isNull();
        assertThat(problem.toString()).doesNotContain("SecretEntityMarker").doesNotContain("4711");
    }

    @Test
    void invalidTransitionBecomesConflictWithFixedDetail() {
        InvalidTicketTransitionException ex =
                new InvalidTicketTransitionException(TicketStatus.CLOSED, TicketStatus.ANSWERED);

        ResponseEntity<Object> response = handler.handleInvalidTransition(ex, request);

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getDetail()).isEqualTo("The ticket's current status does not allow this action.");
        assertThat(problem.getProperties()).isNull();
        assertThat(problem.toString()).doesNotContain(ex.getMessage()).doesNotContain("CLOSED").doesNotContain("ANSWERED");
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
