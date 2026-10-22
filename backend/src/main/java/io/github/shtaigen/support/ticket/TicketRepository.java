package io.github.shtaigen.support.ticket;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TicketRepository extends JpaRepository<Ticket, Long>, AgentTicketQueries {

    /**
     * Any ticket, with its customer and assignee loaded (FR-09, FR-10). There is no ownership
     * filter: agents see every ticket.
     */
    @Query("select t from Ticket t join fetch t.customer left join fetch t.assignee where t.id = :id")
    Optional<Ticket> findWithPeopleById(@Param("id") Long id);

    /**
     * A customer's own ticket. Ownership is part of the query, so a ticket of another customer is
     * not found, the same as a missing one (BR-01, ADR-009).
     */
    Optional<Ticket> findByIdAndCustomerId(Long id, Long customerId);

    /**
     * A customer's tickets in all statuses, newest activity first (FR-04, NFR-05, BR-01).
     */
    @Query("select new io.github.shtaigen.support.ticket.CustomerTicketSummary(t.id, t.subject, t.category,"
            + " t.referenceNumber, t.status, t.createdAt, t.lastMessageAt) from Ticket t"
            + " where t.customer.id = :customerId order by t.lastMessageAt desc, t.id desc")
    List<CustomerTicketSummary> findSummariesOfCustomer(@Param("customerId") Long customerId);

    /**
     * A customer's tickets in the given statuses, newest activity first (FR-04, NFR-05, BR-01).
     *
     * @param statuses must not be empty; use {@link #findSummariesOfCustomer} for no filter
     */
    @Query("select new io.github.shtaigen.support.ticket.CustomerTicketSummary(t.id, t.subject, t.category,"
            + " t.referenceNumber, t.status, t.createdAt, t.lastMessageAt) from Ticket t"
            + " where t.customer.id = :customerId and t.status in :statuses"
            + " order by t.lastMessageAt desc, t.id desc")
    List<CustomerTicketSummary> findSummariesOfCustomerInStatuses(@Param("customerId") Long customerId,
                                                                  @Param("statuses") Collection<TicketStatus> statuses);
}
