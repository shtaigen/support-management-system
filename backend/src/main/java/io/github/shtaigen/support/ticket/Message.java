package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A message in a ticket thread. Mapped to the V1 {@code messages} table. Messages are immutable.
 */
@Entity
@Table(name = "messages")
public class Message {

    // BR-11. The column is text, so this limit exists only in the application.
    public static final int BODY_MAX_LENGTH = 4000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false, updatable = false)
    private User author;

    @Column(name = "body", nullable = false, updatable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
        // for JPA
    }

    /**
     * Creates a message. The body is stored as given (not stripped) to keep its formatting.
     */
    Message(Ticket ticket, User author, String body) {
        this.ticket = Objects.requireNonNull(ticket, "ticket must not be null");
        this.author = Objects.requireNonNull(author, "author must not be null");
        this.body = requireBody(body);
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireBody(String body) {
        Objects.requireNonNull(body, "body must not be null");
        if (body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
        if (body.length() > BODY_MAX_LENGTH) {
            throw new IllegalArgumentException("body must be at most " + BODY_MAX_LENGTH + " characters");
        }
        return body;
    }

    public Long getId() {
        return id;
    }

    public Ticket getTicket() {
        return ticket;
    }

    public User getAuthor() {
        return author;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
