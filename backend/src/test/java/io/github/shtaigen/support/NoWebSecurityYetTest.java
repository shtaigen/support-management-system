package io.github.shtaigen.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

// Step 4b adds spring-security-oauth2-jose only to issue tokens. Until the resource server is
// configured (step 4c), no Spring Security web auto-configuration may be active. Replace in 4c.
@SpringBootTest
@AutoConfigureMockMvc
class NoWebSecurityYetTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvcTester mvc;

    @Test
    void noSecurityFilterChainIsRegistered() {
        assertThat(context.containsBean("springSecurityFilterChain")).isFalse();
    }

    @Test
    void healthEndpointNeedsNoCredentials() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }
}
