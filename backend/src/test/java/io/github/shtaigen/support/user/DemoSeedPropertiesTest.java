package io.github.shtaigen.support.user;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DemoSeedPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(context -> context.getEnvironment().setActiveProfiles("demo"))
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(PasswordEncoder.class, BCryptPasswordEncoder::new)
            .withUserConfiguration(DemoAccountSeeder.class);

    @Test
    void missingDemoPasswordFailsStartup() {
        contextRunner.run(context -> assertThat(context)
                .getFailure()
                .hasRootCauseInstanceOf(BindValidationException.class));
    }

    @Test
    void blankDemoPasswordFailsStartup() {
        contextRunner
                .withPropertyValues("app.seed.demo-password= ")
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasRootCauseInstanceOf(BindValidationException.class));
    }
}
