package io.github.shtaigen.support.common;

import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import java.util.List;

/**
 * 400 for a request field that passed bean validation but failed a check in the service, such as
 * an assignee that is not an agent (FR-12). The body is the same as for bean validation:
 * {@code errors[{field, message}]}. There is no rejected value to leak.
 */
public class InvalidFieldException extends ErrorResponseException {

    public InvalidFieldException(String field, String message) {
        super(HttpStatus.BAD_REQUEST,
                ApiExceptionHandler.validationProblem(List.of(new ApiExceptionHandler.FieldViolation(field, message))),
                null);
    }
}
