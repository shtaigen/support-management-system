package io.github.shtaigen.support.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    /**
     * A customer's own ticket. Ownership is part of the query, so a ticket of another customer is
     * not found, the same as a missing one (BR-01, ADR-009).
     */
    Optional<Ticket> findByIdAndCustomerId(Long id, Long customerId);
}
