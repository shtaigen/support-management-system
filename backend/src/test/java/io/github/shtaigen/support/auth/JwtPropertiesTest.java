package io.github.shtaigen.support.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withPropertyValues("app.jwt.ttl=60m", "app.jwt.issuer=test")
            .withUserConfiguration(JwtConfig.class);

    @Test
    void missingSecretFailsStartup() {
        contextRunner.run(context -> assertThat(context)
                .getFailure()
                .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void tooShortSecretFailsStartupWithoutRevealingIt() {
        String secret = secretOfBytes(31);

        contextRunner
                .withPropertyValues("app.jwt.secret=" + secret)
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("at least 32 bytes")
                        .hasMessageNotContaining(secret));
    }

    @Test
    void nonBase64SecretFailsStartup() {
        contextRunner
                .withPropertyValues("app.jwt.secret=not base64 !!! " + "x".repeat(40))
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Base64"));
    }

    @Test
    void validSecretCreatesEncoder() {
        contextRunner
                .withPropertyValues("app.jwt.secret=" + secretOfBytes(32))
                .run(context -> assertThat(context).hasSingleBean(JwtEncoder.class));
    }

    private static String secretOfBytes(int length) {
        return Base64.getEncoder().encodeToString(new byte[length]);
    }
}
