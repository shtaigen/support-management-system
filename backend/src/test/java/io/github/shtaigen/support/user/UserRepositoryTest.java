package io.github.shtaigen.support.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Runs against the real Postgres; each test rolls back. Emails use the repo-test-* prefix
// so they never collide with seeded demo accounts that persist in the local database.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserRepositoryTest {

    @Autowired
    private UserRepository users;

    @Test
    void findsUserRegardlessOfCaseAndSurroundingWhitespace() {
        User saved = users.saveAndFlush(
                new User("repo-test-lookup@example.com", "hash", "Lookup", UserRole.CUSTOMER));

        assertThat(users.findByEmail("  Repo-Test-Lookup@Example.COM "))
                .get()
                .extracting(User::getId)
                .isEqualTo(saved.getId());
    }

    @Test
    void unknownEmailReturnsEmpty() {
        assertThat(users.findByEmail("repo-test-unknown@example.com")).isEmpty();
    }

    @Test
    void duplicateEmailIsRejectedByDatabase() {
        users.saveAndFlush(new User("repo-test-dup@example.com", "hash", "First", UserRole.CUSTOMER));

        assertThatThrownBy(() -> users.saveAndFlush(
                new User(" REPO-TEST-DUP@example.com", "hash", "Second", UserRole.AGENT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
