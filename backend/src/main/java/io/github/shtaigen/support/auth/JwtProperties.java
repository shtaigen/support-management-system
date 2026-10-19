package io.github.shtaigen.support.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Settings for the access tokens the application signs (ADR-009, NFR-01).
 *
 * @param secret Base64-encoded HMAC key of at least 256 bits. No default: set it with
 *               {@code APP_JWT_SECRET}; startup fails if it is missing or too short.
 * @param ttl    token lifetime
 * @param issuer value of the {@code iss} claim
 */
@Validated
@ConfigurationProperties("app.jwt")
public record JwtProperties(@NotBlank String secret, @NotNull Duration ttl, @NotBlank String issuer) {

    static final int MIN_SECRET_BYTES = 32;

    /**
     * Decodes {@link #secret()} into an HS256 key. Error messages name the property, never its value.
     *
     * @throws IllegalStateException if the secret is not Base64 or shorter than 256 bits
     */
    public SecretKey signingKey() {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.jwt.secret must be Base64-encoded");
        }
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret must decode to at least " + MIN_SECRET_BYTES + " bytes (256 bits)");
        }
        return new SecretKeySpec(key, "HmacSHA256");
    }

    @Override
    public String toString() {
        return "JwtProperties[secret=***, ttl=" + ttl + ", issuer=" + issuer + "]";
    }
}
