package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.user.User;
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
 * <p>Timestamps are set in Java: {@code createdAt}, {@code updatedAt} and {@code lastMessageAt}
 * start at the creation instant, {@code updatedAt} is refreshed on every update (BR-13), and
 * {@code lastMessageAt} is changed only by domain methods.
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

    /**
     * Creates a new, unassigned ticket in status OPEN (TR-01, BR-03).
     *
     * @param referenceNumber optional; null or blank means none
     */
    public Ticket(User customer, String subject, TicketCategory category, String referenceNumber) {
        this.customer = Objects.requireNonNull(customer, "customer must not be null");
        this.subject = normalizeSubject(subject);
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.referenceNumber = normalizeReferenceNumber(referenceNumber);
        this.status = TicketStatus.OPEN;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        this.createdAt = now;
        this.updatedAt = now;
        this.lastMessageAt = now;
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
