package io.github.shtaigen.support.auth;

import io.github.shtaigen.support.auth.LoginResponse.LoginUser;
import io.github.shtaigen.support.auth.TokenIssuer.IssuedToken;
import io.github.shtaigen.support.user.User;
import io.github.shtaigen.support.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Email + password login (FR-01). Deliberately not {@code @Transactional}: the BCrypt check takes
 * about 100 ms and would hold a pooled connection; the repository call has its own transaction.
 */
@Service
class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokenIssuer;

    /**
     * Hash of a random password, checked when the email is unknown so that the response time does
     * not reveal whether an account exists. Made by the same encoder, so it has the same cost.
     */
    private final String dummyHash;

    AuthService(UserRepository users, PasswordEncoder passwordEncoder, TokenIssuer tokenIssuer) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    LoginResponse login(LoginRequest request) {
        Optional<User> found = users.findByEmail(request.email());
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw new InvalidCredentialsException();
        }
        User user = found.get();
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        IssuedToken token = tokenIssuer.issue(user);
        return new LoginResponse(token.value(), token.expiresAt(),
                new LoginUser(user.getId(), user.getName(), user.getRole()));
    }
}
