package io.github.shtaigen.support.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class RoleClaimAuthoritiesConverterTest {

    private final RoleClaimAuthoritiesConverter converter = new RoleClaimAuthoritiesConverter();

    @Test
    void customerRoleMapsToRoleCustomer() {
        assertThat(authorities(jwt(claims -> claims.claim("role", "CUSTOMER")))).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void agentRoleMapsToRoleAgent() {
        assertThat(authorities(jwt(claims -> claims.claim("role", "AGENT")))).containsExactly("ROLE_AGENT");
    }

    @Test
    void missingRoleGivesNoAuthorities() {
        assertThat(authorities(jwt(claims -> { }))).isEmpty();
    }

    @Test
    void unknownRoleGivesNoAuthorities() {
        assertThat(authorities(jwt(claims -> claims.claim("role", "ADMIN")))).isEmpty();
    }

    @Test
    void roleNameIsCaseSensitive() {
        assertThat(authorities(jwt(claims -> claims.claim("role", "customer")))).isEmpty();
    }

    @Test
    void nonStringRoleGivesNoAuthorities() {
        assertThat(authorities(jwt(claims -> claims.claim("role", List.of("AGENT"))))).isEmpty();
    }

    @Test
    void principalNameIsSubject() {
        Authentication authentication = new SecurityConfig().jwtAuthenticationConverter()
                .convert(jwt(claims -> claims.claim("role", "AGENT")));

        assertThat(authentication.getName()).isEqualTo("42");
        // Spring Security 7 also adds a FACTOR_BEARER authority; only roles matter here.
        assertThat(authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_")))
                .containsExactly("ROLE_AGENT");
    }

    private List<String> authorities(Jwt jwt) {
        return converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwt(Consumer<Jwt.Builder> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject("42")
                .issuedAt(Instant.parse("2026-01-01T10:00:00Z"))
                .expiresAt(Instant.parse("2026-01-01T11:00:00Z"));
        claims.accept(builder);
        return builder.build();
    }
}
