package io.github.shtaigen.support.user;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @Test
    void emailIsStrippedAndLowercased() {
        User user = new User("  Alice@Example.COM ", "hash", "Alice", UserRole.CUSTOMER);

        assertThat(user.getEmail()).isEqualTo("alice@example.com");
    }

    @Test
    void emailIsLowercasedIndependentOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            // Turkish lowercases "I" to a dotless "ı" under the default locale.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(User.normalizeEmail("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    void blankEmailIsRejected() {
        assertThatThrownBy(() -> new User("   ", "hash", "Alice", UserRole.CUSTOMER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tooLongEmailIsRejected() {
        String email = "a".repeat(User.EMAIL_MAX_LENGTH - "@x.io".length() + 1) + "@x.io";

        assertThatThrownBy(() -> new User(email, "hash", "Alice", UserRole.CUSTOMER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordHashIsStoredAsGiven() {
        User user = new User("a@x.io", " Hash ", "Alice", UserRole.AGENT);

        assertThat(user.getPasswordHash()).isEqualTo(" Hash ");
    }

    @Test
    void blankPasswordHashIsRejected() {
        assertThatThrownBy(() -> new User("a@x.io", " ", "Alice", UserRole.AGENT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
