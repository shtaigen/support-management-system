package io.github.shtaigen.support.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;

/**
 * A customer or an agent. Mapped to the V1 {@code users} table.
 */
@Entity
@Table(name = "users")
public class User {

    public static final int EMAIL_MAX_LENGTH = 320;
    public static final int PASSWORD_HASH_MAX_LENGTH = 255;
    public static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = EMAIL_MAX_LENGTH)
    private String email;

    @Column(name = "password_hash", nullable = false, length = PASSWORD_HASH_MAX_LENGTH)
    private String passwordHash;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // for JPA
    }

    public User(String email, String passwordHash, String name, UserRole role) {
        this.email = requireText(normalizeEmail(email), "email", EMAIL_MAX_LENGTH);
        this.passwordHash = requireText(passwordHash, "passwordHash", PASSWORD_HASH_MAX_LENGTH);
        this.name = requireText(Objects.requireNonNull(name, "name must not be null").strip(), "name", NAME_MAX_LENGTH);
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Normalizes an email for storage and lookup (BR-14, ADR-005): trimmed and lowercased with
     * {@link Locale#ROOT}. Use the same function before every query by email.
     */
    public static String normalizeEmail(String email) {
        Objects.requireNonNull(email, "email must not be null");
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public UserRole getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
