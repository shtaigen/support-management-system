package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A support ticket opened by a customer. Mapped to the V1 {@code tickets} table.
 *
 * <p>Timestamps are set in Java: {@code createdAt} and {@code updatedAt} start at the creation
 * instant, {@code updatedAt} is refreshed on every update (BR-13), and {@code lastMessageAt} is
 * set only by {@link #postMessage}, to the {@code createdAt} of the latest message.
 *
 * <p>All state changes go through the domain methods {@link #open}, {@link #postMessage},
 * {@link #changeStatus} and {@link #assignTo}. Who may call which operation is checked by the
 * service layer.
 */
@Entity
@Table(name = "tickets")
public class Ticket {

    public static final int SUBJECT_MAX_LENGTH = 200;
    public static final int REFERENCE_NUMBER_MAX_LENGTH = 50;

    // BR-18, checked after normalization to uppercase.
    private static final Pattern REFERENCE_NUMBER_PATTERN = Pattern.compile("[A-Z0-9-]+");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Column(name = "subject", nullable = false, length = SUBJECT_MAX_LENGTH)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private TicketCategory category;

    @Column(name = "reference_number", length = REFERENCE_NUMBER_MAX_LENGTH)
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TicketStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Ticket() {
        // for JPA
    }

    private Ticket(User customer, String subject, TicketCategory category, String referenceNumber) {
        this.customer = customer;
        this.subject = normalizeSubject(subject);
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.referenceNumber = normalizeReferenceNumber(referenceNumber);
        this.status = TicketStatus.OPEN;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
        this.lastMessageAt = now;
    }

    /**
     * Opens a new, unassigned ticket in status OPEN with its first message (TR-01, BR-03, BR-16).
     * The first message goes through {@link #postMessage}, so {@code lastMessageAt} equals its
     * {@code createdAt}.
     *
     * @param referenceNumber optional; null or blank means none
     */
    public static OpenedTicket open(User customer, String subject, TicketCategory category,
                                    String referenceNumber, String firstMessageBody) {
        Objects.requireNonNull(customer, "customer must not be null");
        if (customer.getRole() != UserRole.CUSTOMER) {
            throw new IllegalArgumentException("customer must have role " + UserRole.CUSTOMER);
        }
        Ticket ticket = new Ticket(customer, subject, category, referenceNumber);
        Message firstMessage = ticket.postMessage(customer, firstMessageBody);
        return new OpenedTicket(ticket, firstMessage);
    }

    /**
     * Records a new message on this ticket. This is the only way to create a message (BR-17): it
     * applies the status rules (TR-02..TR-06, BR-06), assigns the first replying agent (BR-09) and
     * sets {@code lastMessageAt} to the message's {@code createdAt} (BR-13).
     *
     * @throws InvalidTicketTransitionException if an agent posts to a CLOSED ticket (BR-06)
     * @throws IllegalArgumentException if a customer other than the ticket's customer is the author,
     *                                  or the body is invalid (BR-11)
     */
    public Message postMessage(User author, String body) {
        Objects.requireNonNull(author, "author must not be null");
        TicketStatus next = statusAfterMessageBy(author);
        Message message = new Message(this, author, body);
        if (author.getRole() == UserRole.AGENT && assignee == null) {
            assignee = author;
        }
        status = next;
        lastMessageAt = message.getCreatedAt();
        return message;
    }

    private TicketStatus statusAfterMessageBy(User author) {
        return switch (author.getRole()) {
            case AGENT -> {
                if (status == TicketStatus.CLOSED) {
                    throw new InvalidTicketTransitionException(status, TicketStatus.ANSWERED);
                }
                yield TicketStatus.ANSWERED;
            }
            case CUSTOMER -> {
                if (!sameUser(author, customer)) {
                    throw new IllegalArgumentException("author must be the ticket's customer");
                }
                yield status == TicketStatus.OPEN ? TicketStatus.OPEN : TicketStatus.CUSTOMER_REPLIED;
            }
        };
    }

    /**
     * Changes the status manually: any non-closed status to CLOSED (TR-07), or CLOSED to OPEN
     * (TR-08). The assignee is kept.
     *
     * @throws InvalidTicketTransitionException for every other target, including the current
     *                                          status (BR-07)
     */
    public void changeStatus(TicketStatus target) {
        Objects.requireNonNull(target, "target must not be null");
        boolean allowed = (target == TicketStatus.CLOSED && status != TicketStatus.CLOSED)
                || (target == TicketStatus.OPEN && status == TicketStatus.CLOSED);
        if (!allowed) {
            throw new InvalidTicketTransitionException(status, target);
        }
        status = target;
    }

    /**
     * Assigns or reassigns the ticket to an agent (FR-12, BR-10). Assigning the current assignee
     * again changes nothing. Unassigning is not supported.
     *
     * @throws IllegalArgumentException if {@code agent} does not have role AGENT
     */
    public void assignTo(User agent) {
        Objects.requireNonNull(agent, "agent must not be null");
        if (agent.getRole() != UserRole.AGENT) {
            throw new IllegalArgumentException("assignee must have role " + UserRole.AGENT);
        }
        if (assignee != null && sameUser(assignee, agent)) {
            return;
        }
        assignee = agent;
    }

    // User has no equals(). Ids are read through the getter, which does not initialize a
    // Hibernate proxy; the identity check covers users that are not persisted yet.
    private static boolean sameUser(User a, User b) {
        if (a == b) {
            return true;
        }
        Long aId = a.getId();
        Long bId = b.getId();
        return aId != null && bId != null && aId.equals(bId);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static String normalizeSubject(String subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        String stripped = subject.strip();
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (stripped.length() > SUBJECT_MAX_LENGTH) {
            throw new IllegalArgumentException("subject must be at most " + SUBJECT_MAX_LENGTH + " characters");
        }
        return stripped;
    }

    private static String normalizeReferenceNumber(String referenceNumber) {
        if (referenceNumber == null || referenceNumber.isBlank()) {
            return null;
        }
        String normalized = referenceNumber.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > REFERENCE_NUMBER_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "referenceNumber must be at most " + REFERENCE_NUMBER_MAX_LENGTH + " characters");
        }
        if (!REFERENCE_NUMBER_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("referenceNumber may contain only letters, digits and hyphens");
        }
        return normalized;
    }

    public Long getId() {
        return id;
    }

    public User getCustomer() {
        return customer;
    }

    public User getAssignee() {
        return assignee;
    }

    public String getSubject() {
        return subject;
    }

    public TicketCategory getCategory() {
        return category;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public Long getVersion() {
        return version;
    }
}
