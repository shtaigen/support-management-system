package io.github.shtaigen.support.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a user by email as typed by the user (BR-14): the input is normalized with
     * {@link User#normalizeEmail} before the lookup. Use this method, not {@link #findUserByEmail}.
     */
    default Optional<User> findByEmail(String rawEmail) {
        return findUserByEmail(User.normalizeEmail(rawEmail));
    }

    /**
     * Exact match on the stored email, served by the {@code users_email_uk} index. Expects an
     * already-normalized email; callers should use {@link #findByEmail} instead.
     */
    Optional<User> findUserByEmail(String normalizedEmail);
}
