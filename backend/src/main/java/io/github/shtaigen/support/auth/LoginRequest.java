package io.github.shtaigen.support.auth;

import io.github.shtaigen.support.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login input (FR-01). The email is only stripped here so that format validation accepts
 * surrounding whitespace; case normalization happens in the repository lookup.
 */
record LoginRequest(
        @NotBlank @Email @Size(max = User.EMAIL_MAX_LENGTH) String email,
        @NotBlank @MaxUtf8Bytes(LoginRequest.PASSWORD_MAX_BYTES) String password) {

    /**
     * BCrypt uses at most 72 bytes of input and Spring Security rejects longer passwords with an
     * exception. Longer input is a validation error (400), so it never reaches the encoder.
     */
    static final int PASSWORD_MAX_BYTES = 72;

    LoginRequest {
        email = email == null ? null : email.strip();
    }

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=***]";
    }
}
