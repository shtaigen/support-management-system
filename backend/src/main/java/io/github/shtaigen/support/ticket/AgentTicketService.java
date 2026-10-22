package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.common.InvalidFieldException;
import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import org.jspecify.annotations.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * Agent ticket operations (FR-07, FR-09..FR-12). Agents see every ticket, so there is no
 * ownership filter; the role is enforced by the URL zone (ADR-009).
 */
@Service
class AgentTicketService {

    private final TicketRepository tickets;
    private final MessageRepository messages;
    private final UserRepository users;

    AgentTicketService(TicketRepository tickets, MessageRepository messages, UserRepository users) {
        this.tickets = tickets;
        this.messages = messages;
        this.users = users;
    }

    /**
     * The queue of all tickets, newest activity first (FR-07, NFR-05).
     *
     * @param statuses   null elements (empty query values) are ignored; no remaining status means all
     * @param unassigned only {@code true} filters; {@code false} is the same as absent
     */
    @Transactional(readOnly = true)
    List<AgentTicketSummary> list(@Nullable Collection<TicketStatus> statuses, @Nullable Long assigneeId,
                                  @Nullable Boolean unassigned, @Nullable Long customerId) {
        return tickets.findAgentQueue(new AgentTicketQuery(TicketStatusFilter.of(statuses), assigneeId,
                Boolean.TRUE.equals(unassigned), customerId));
    }

    /**
     * @throws TicketNotFoundException if the ticket does not exist
     */
    @Transactional(readOnly = true)
    AgentTicketResponse get(long ticketId) {
        Ticket ticket = tickets.findWithPeopleById(ticketId).orElseThrow(TicketNotFoundException::new);
        return AgentTicketResponse.from(ticket, messages.findThread(ticket.getId()));
    }

    /**
     * Adds an agent reply (FR-10, TR-02): the status becomes ANSWERED and an unassigned ticket is
     * assigned to the caller (BR-09). A concurrent change to the ticket fails with an
     * optimistic-locking conflict, and the message is rolled back with it (BR-15).
     *
     * @throws IllegalStateException           if the token's user does not exist or is not an agent;
     *                                         the MVP never deletes users or changes roles, so this
     *                                         is a bug
     * @throws TicketNotFoundException         if the ticket does not exist
     * @throws InvalidTicketTransitionException if the ticket is CLOSED (BR-06)
     */
    @Transactional
    AgentTicketResponse postMessage(long agentId, long ticketId, PostMessageRequest request) {
        User agent = users.findById(agentId)
                .orElseThrow(() -> new IllegalStateException("User " + agentId + " not found"));
        if (agent.getRole() != UserRole.AGENT) {
            throw new IllegalStateException("User " + agentId + " is not an agent");
        }
        Ticket ticket = tickets.findWithPeopleById(ticketId).orElseThrow(TicketNotFoundException::new);
        messages.save(ticket.postMessage(agent, request.body()));
        return respond(ticket);
    }

    /**
     * Closes (TR-07) or reopens (TR-08) the ticket; the assignee is kept.
     *
     * @throws TicketNotFoundException                   if the ticket does not exist
     * @throws ObjectOptimisticLockingFailureException   if the request's version is not the current
     *                                                   one, or another change is committed first (BR-15)
     * @throws InvalidTicketTransitionException          for any other transition (BR-07)
     */
    @Transactional
    AgentTicketResponse changeStatus(long ticketId, ChangeTicketStatusRequest request) {
        Ticket ticket = loadAtVersion(ticketId, request.version());
        ticket.changeStatus(request.status());
        return respond(ticket);
    }

    /**
     * Assigns or reassigns the ticket to an agent, in any status (BR-10). Assigning the current
     * assignee again changes nothing, so version and updatedAt stay the same.
     *
     * @throws InvalidFieldException                     if the assignee does not exist or is not an
     *                                                   agent; checked before the ticket is loaded
     * @throws TicketNotFoundException                   if the ticket does not exist
     * @throws ObjectOptimisticLockingFailureException   if the request's version is not the current
     *                                                   one, or another change is committed first (BR-15)
     */
    @Transactional
    AgentTicketResponse assign(long ticketId, AssignTicketRequest request) {
        // The same message for both cases.
        User assignee = users.findById(request.assigneeId())
                .filter(user -> user.getRole() == UserRole.AGENT)
                .orElseThrow(() -> new InvalidFieldException("assigneeId", "must be an existing agent"));
        Ticket ticket = loadAtVersion(ticketId, request.version());
        ticket.assignTo(assignee);
        return respond(ticket);
    }

    /**
     * The ticket, if the client's view of it is current (ADR-006). {@code @Version} compares only
     * with the version read in this transaction, so it cannot detect a stale view; this check runs
     * before the domain method, so a stale view always gets the optimistic-locking 409.
     */
    private Ticket loadAtVersion(long ticketId, long expectedVersion) {
        Ticket ticket = tickets.findWithPeopleById(ticketId).orElseThrow(TicketNotFoundException::new);
        if (ticket.getVersion().longValue() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(Ticket.class, ticketId);
        }
        return ticket;
    }

    private AgentTicketResponse respond(Ticket ticket) {
        // Write the ticket before building the response, which shows version and updatedAt. Otherwise
        // @PreUpdate runs again at commit and stores a later updatedAt than the one returned.
        tickets.flush();
        return AgentTicketResponse.from(ticket, messages.findThread(ticket.getId()));
    }
}
