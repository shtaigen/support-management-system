package io.github.shtaigen.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

// The resource server is the only web security: one filter chain, our own JWT decoder, and no
// UserDetailsService, so Boot never logs "Using generated security password".
@SpringBootTest
class WebSecuritySetupTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void singleSecurityFilterChainIsRegistered() {
        assertThat(context.containsBean("springSecurityFilterChain")).isTrue();
        assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(1);
    }

    @Test
    void noUserDetailsServiceSoNoGeneratedPassword() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }

    @Test
    void jwtDecoderIsTheApplicationsOwn() {
        assertThat(context.getBeansOfType(JwtDecoder.class).values())
                .singleElement()
                .isInstanceOf(NimbusJwtDecoder.class);
    }
}
