package io.github.shtaigen.support.auth;

import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import io.github.shtaigen.support.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private static final String DUMMY_HASH = "$2a$10$dummy";
    private static final String USER_HASH = "$2a$10$user";

    private final UserRepository users = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final TokenIssuer tokenIssuer = mock(TokenIssuer.class);

    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);
        authService = new AuthService(users, passwordEncoder, tokenIssuer);
    }

    @Test
    void unknownEmailStillChecksPasswordAgainstDummyHash() {
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        catchThrowableOfType(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("nobody@example.com", "pw")));

        verify(passwordEncoder, times(1)).matches("pw", DUMMY_HASH);
        verifyNoInteractions(tokenIssuer);
    }

    @Test
    void wrongPasswordAndUnknownEmailFailTheSameWay() {
        User user = new User("someone@example.com", USER_HASH, "Someone", UserRole.CUSTOMER);
        when(users.findByEmail("someone@example.com")).thenReturn(Optional.of(user));
        when(users.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);

        InvalidCredentialsException wrongPassword = catchThrowableOfType(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("someone@example.com", "wrong")));
        InvalidCredentialsException unknownEmail = catchThrowableOfType(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("nobody@example.com", "wrong")));

        assertThat(wrongPassword).isNotNull();
        assertThat(unknownEmail).isNotNull();
        assertThat(unknownEmail.getBody()).isEqualTo(wrongPassword.getBody());
        assertThat(unknownEmail.getStatusCode()).isEqualTo(wrongPassword.getStatusCode());

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, times(2)).matches(eq("wrong"), hashes.capture());
        assertThat(hashes.getAllValues()).containsExactly(USER_HASH, DUMMY_HASH);
        verifyNoInteractions(tokenIssuer);
    }
}
