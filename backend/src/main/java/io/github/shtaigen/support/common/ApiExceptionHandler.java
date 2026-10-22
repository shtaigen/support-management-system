package io.github.shtaigen.support.common;

import io.github.shtaigen.support.ticket.InvalidTicketTransitionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns errors into RFC 9457 Problem Details (NFR-03). The base class already handles Spring MVC
 * exceptions and every {@link org.springframework.web.ErrorResponseException}, so API errors with a
 * fixed status should extend that class instead of adding a handler here.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record FieldViolation(String field, String message) {
    }

    /**
     * 400 with the invalid fields and their messages. Rejected values are never included: they can
     * be passwords.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * 400 for a path variable or parameter that cannot be converted, such as a non-numeric id. The
     * base class would echo the rejected value in the detail.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = Objects.requireNonNullElse(ex.getPropertyName(), "request");
        List<FieldViolation> errors = List.of(new FieldViolation(field, "has an invalid format"));
        return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * 409 when another transaction changed the same entity first (BR-15, ADR-006). The detail is
     * fixed: the exception message names the entity and its id.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @Nullable ResponseEntity<Object> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The data was changed by another request. Reload it and try again.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /**
     * 409 when the ticket's status does not allow the operation (BR-06, BR-07, ADR-008). The detail
     * is fixed and differs from the optimistic-locking one: the exception message names the
     * statuses.
     */
    @ExceptionHandler(InvalidTicketTransitionException.class)
    @Nullable ResponseEntity<Object> handleInvalidTransition(InvalidTicketTransitionException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The ticket's current status does not allow this action.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.CONFLICT, request);
    }

    /**
     * 500 for any exception without a more specific handler. Without this, the exception would go
     * through an ERROR dispatch to {@code /error}, which passes the security filter chain again and
     * can turn into a 401, a 403 or an empty body. The detail is fixed: exception messages are
     * logged, never returned. Security exceptions are rethrown so Spring Security still answers
     * them with 401 or 403.
     */
    @ExceptionHandler(Exception.class)
    @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw (RuntimeException) ex;
        }
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    // Also used by InvalidFieldException, so its 400 has the same body.
    static ProblemDetail validationProblem(List<FieldViolation> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed.");
        problem.setProperties(Map.of("errors", errors));
        return problem;
    }
}
