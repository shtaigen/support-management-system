package io.github.shtaigen.support.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Creates the MVP agent and demo customer accounts (no self-registration, requirements §8).
 * Runs only under the {@code demo} profile. Accounts that already exist are left unchanged.
 */
@Component
@Profile("demo")
@EnableConfigurationProperties(DemoSeedProperties.class)
class DemoAccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoAccountSeeder.class);

    private record Account(String email, String name, UserRole role) {
    }

    private static final List<Account> ACCOUNTS = List.of(
            new Account("agent1@example.com", "Alice Agent", UserRole.AGENT),
            new Account("agent2@example.com", "Bob Agent", UserRole.AGENT),
            new Account("customer1@example.com", "Carol Customer", UserRole.CUSTOMER),
            new Account("customer2@example.com", "Dave Customer", UserRole.CUSTOMER));

    private final UserRepository users;
    private final DemoSeedProperties properties;
    private final PasswordEncoder passwordEncoder;

    DemoAccountSeeder(UserRepository users, DemoSeedProperties properties, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (Account account : ACCOUNTS) {
            if (users.findByEmail(account.email()).isEmpty()) {
                String hash = passwordEncoder.encode(properties.demoPassword());
                users.save(new User(account.email(), hash, account.name(), account.role()));
                log.info("Created demo account {}", account.email());
            }
        }
    }
}
