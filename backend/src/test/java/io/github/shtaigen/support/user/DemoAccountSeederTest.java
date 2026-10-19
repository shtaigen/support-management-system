package io.github.shtaigen.support.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("demo")
class DemoAccountSeederTest {

    private static final Map<String, UserRole> EXPECTED_ROLES = Map.of(
            "agent1@example.com", UserRole.AGENT,
            "agent2@example.com", UserRole.AGENT,
            "customer1@example.com", UserRole.CUSTOMER,
            "customer2@example.com", UserRole.CUSTOMER);

    @Autowired
    private UserRepository users;

    @Autowired
    private DemoSeedProperties properties;

    @Autowired
    private DemoAccountSeeder seeder;

    @Test
    void seedAccountsExistWithExpectedRoles() {
        EXPECTED_ROLES.forEach((email, role) ->
                assertThat(users.findByEmail(email)).get().extracting(User::getRole).isEqualTo(role));
    }

    @Test
    void seedPasswordHashesMatchDemoPassword() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        List<User> seeded = EXPECTED_ROLES.keySet().stream()
                .map(email -> users.findByEmail(email).orElseThrow())
                .toList();

        assertThat(seeded).allSatisfy(user ->
                assertThat(encoder.matches(properties.demoPassword(), user.getPasswordHash())).isTrue());
    }

    @Test
    void seedingAgainCreatesNoDuplicates() {
        long before = users.count();

        seeder.run(new DefaultApplicationArguments());

        assertThat(users.count()).isEqualTo(before);
    }
}
