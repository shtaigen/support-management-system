package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import java.util.ArrayList;
import java.util.List;

/**
 * Criteria implementation of {@link AgentTicketQueries}. A predicate is added only for a filter
 * that is present, so the SQL has no null-parameter checks and never an empty IN list.
 */
class AgentTicketQueriesImpl implements AgentTicketQueries {

    private final EntityManager em;

    AgentTicketQueriesImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public List<AgentTicketSummary> findAgentQueue(AgentTicketQuery query) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<AgentTicketSummary> cq = cb.createQuery(AgentTicketSummary.class);
        Root<Ticket> t = cq.from(Ticket.class);
        Join<Ticket, User> customer = t.join("customer");
        Join<Ticket, User> assignee = t.join("assignee", JoinType.LEFT);

        List<Predicate> where = new ArrayList<>();
        if (!query.statuses().isEmpty()) {
            where.add(t.get("status").in(query.statuses()));
        }
        // The role checks make an id of the wrong role match nothing (FR-07), whatever the
        // database holds: it does not check the assignee's role itself (BR-10).
        if (query.assigneeId() != null) {
            where.add(cb.equal(assignee.get("id"), query.assigneeId()));
            where.add(cb.equal(assignee.get("role"), UserRole.AGENT));
        }
        if (query.unassigned()) {
            where.add(cb.isNull(t.get("assignee")));
        }
        if (query.customerId() != null) {
            where.add(cb.equal(customer.get("id"), query.customerId()));
            where.add(cb.equal(customer.get("role"), UserRole.CUSTOMER));
        }

        cq.select(cb.construct(AgentTicketSummary.class,
                        t.get("id"), t.get("subject"), t.get("category"), t.get("referenceNumber"),
                        t.get("status"), customer.get("id"), customer.get("name"), customer.get("email"),
                        assignee.get("id"), assignee.get("name"), t.get("lastMessageAt")))
                .where(where.toArray(Predicate[]::new))
                .orderBy(cb.desc(t.get("lastMessageAt")), cb.desc(t.get("id")));
        return em.createQuery(cq).getResultList();
    }
}
