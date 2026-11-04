package io.github.shtaigen.support.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
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

    /**
     * Every agent, by name in the database collation, then id (FR-13, NFR-05). The role is checked
     * here because the database does not check it.
     */
    @Query("select new io.github.shtaigen.support.user.AgentRef(u.id, u.name) from User u"
            + " where u.role = io.github.shtaigen.support.user.UserRole.AGENT"
            + " order by u.name, u.id")
    List<AgentRef> findAgents();
}
