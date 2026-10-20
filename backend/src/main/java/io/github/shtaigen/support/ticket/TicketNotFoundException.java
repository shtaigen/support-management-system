package io.github.shtaigen.support.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * 404 for a ticket that does not exist or belongs to another customer. Both cases produce the same
 * response, so it does not reveal whether a ticket exists (BR-01).
 */
class TicketNotFoundException extends ErrorResponseException {

    TicketNotFoundException() {
        super(HttpStatus.NOT_FOUND, problem(), null);
    }

    private static ProblemDetail problem() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Ticket not found.");
        problem.setTitle("Not Found");
        return problem;
    }
}
