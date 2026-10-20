package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Runs against the real Postgres; each test rolls back. Emails use the persistence-test-* prefix
// so they never collide with seeded demo accounts.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TicketPersistenceTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TicketRepository tickets;

    @Autowired
    private MessageRepository messages;

    private User customer;
    private User agent;

    @BeforeEach
    void createUsers() {
        customer = em.persist(new User("persistence-test-customer@example.com", "hash", "Customer", UserRole.CUSTOMER));
        agent = em.persist(new User("persistence-test-agent@example.com", "hash", "Agent", UserRole.AGENT));
    }

    @Test
    @DisplayName("FR-12, BR-13, BR-15: a real assignment updates the row, bumps the version and stores @PreUpdate's updatedAt")
    void assignmentIsWrittenWithNewVersionAndUpdatedAt() {
        long id = openTicket();
        Ticket ticket = tickets.findById(id).orElseThrow();
        Instant createdUpdatedAt = ticket.getUpdatedAt();
        assertThat(ticket.getVersion()).isZero();

        ticket.assignTo(em.find(User.class, agent.getId()));
        em.flush();
        Instant updatedAt = ticket.getUpdatedAt();
        em.clear();

        Ticket reloaded = tickets.findById(id).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1);
        assertThat(reloaded.getAssignee().getId()).isEqualTo(agent.getId());
        assertThat(updatedAt).isAfter(createdUpdatedAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    // Every UPDATE of a @Version entity increments the version, so an unchanged version proves that
    // no UPDATE ran (Hibernate statistics are disabled and would always count zero).
    @Test
    @DisplayName("FR-12: assigning the same agent again executes no UPDATE")
    void sameAssigneeIsNotWritten() {
        long id = openTicket();
        tickets.findById(id).orElseThrow().assignTo(em.find(User.class, agent.getId()));
        em.flush();
        em.clear();
        Ticket ticket = tickets.findById(id).orElseThrow();
        long version = ticket.getVersion();
        Instant updatedAt = ticket.getUpdatedAt();

        ticket.assignTo(em.find(User.class, agent.getId()));
        em.flush();
        em.clear();

        Ticket reloaded = tickets.findById(id).orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(version);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("NFR-05: the thread is ordered by created_at, then id")
    void threadIsOrderedByCreatedAtThenId() {
        OpenedTicket opened = Ticket.open(customer, "Subject", TicketCategory.GENERAL, null, "First");
        Ticket ticket = tickets.save(opened.ticket());
        Message first = messages.save(opened.firstMessage());
        Message second = messages.save(ticket.postMessage(customer, "Second"));
        Message third = messages.save(ticket.postMessage(customer, "Third"));
        em.flush();

        // first and second share a timestamp, so id decides; third is the oldest.
        Instant tie = Instant.parse("2026-01-01T10:00:00Z");
        setCreatedAt(first, tie);
        setCreatedAt(second, tie);
        setCreatedAt(third, tie.minusSeconds(3600));
        em.clear();

        List<Message> thread = messages.findThread(ticket.getId());

        assertThat(thread).extracting(Message::getId)
                .containsExactly(third.getId(), first.getId(), second.getId());
        assertThat(thread).extracting(message -> message.getAuthor().getRole()).containsOnly(UserRole.CUSTOMER);
    }

    private long openTicket() {
        OpenedTicket opened = Ticket.open(customer, "Subject", TicketCategory.GENERAL, null, "First");
        Ticket ticket = tickets.save(opened.ticket());
        messages.save(opened.firstMessage());
        em.flush();
        em.clear();
        return ticket.getId();
    }

    private void setCreatedAt(Message message, Instant createdAt) {
        em.getEntityManager()
                .createNativeQuery("update messages set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", message.getId())
                .executeUpdate();
    }
}
