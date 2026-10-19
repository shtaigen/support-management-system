package io.github.shtaigen.support.user;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the demo account seed. There is no default: a missing or blank password fails
 * startup when the {@code demo} profile is active.
 */
@Validated
@ConfigurationProperties("app.seed")
public record DemoSeedProperties(@NotBlank String demoPassword) {
}
