package io.github.shtaigen.support.user;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
class AgentService {

    private final UserRepository users;

    AgentService(UserRepository users) {
        this.users = users;
    }

    /**
     * Every agent, including the caller (FR-13), sorted by NFR-05.
     */
    @Transactional(readOnly = true)
    List<AgentRef> list() {
        return users.findAgents();
    }
}
