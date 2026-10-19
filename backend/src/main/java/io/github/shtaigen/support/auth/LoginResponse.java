package io.github.shtaigen.support.auth;

import io.github.shtaigen.support.user.UserRole;

import java.time.Instant;

/**
 * Login result (FR-01): the access token, when it expires, and the user the client needs to pick
 * its zone (ADR-011).
 */
record LoginResponse(String accessToken, Instant expiresAt, LoginUser user) {

    record LoginUser(Long id, String name, UserRole role) {
    }

    @Override
    public String toString() {
        return "LoginResponse[accessToken=***, expiresAt=" + expiresAt + ", user=" + user + "]";
    }
}
