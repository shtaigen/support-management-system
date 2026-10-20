package io.github.shtaigen.support.auth;

import io.github.shtaigen.support.user.UserRole;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Maps the single-string {@code role} claim to {@code ROLE_<role>}. Only exact {@link UserRole}
 * names count; a missing, non-string or unknown value gives no authorities, so the token is
 * authenticated but allowed into no zone (403).
 */
class RoleClaimAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        if (!(jwt.getClaims().get(TokenIssuer.ROLE_CLAIM) instanceof String role)) {
            return List.of();
        }
        return Arrays.stream(UserRole.values())
                .filter(known -> known.name().equals(role))
                .<GrantedAuthority>map(known -> new SimpleGrantedAuthority("ROLE_" + known.name()))
                .toList();
    }
}
