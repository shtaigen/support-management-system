package io.github.shtaigen.support.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 401 for any failed login. Unknown email and wrong password produce the same response, so it
 * does not reveal whether an account exists (FR-01).
 */
class InvalidCredentialsException extends ErrorResponseException {

    InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Invalid email or password.");
        problem.setTitle("Unauthorized");
        return problem;
    }
}
