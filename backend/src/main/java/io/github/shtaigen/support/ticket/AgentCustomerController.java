package io.github.shtaigen.support.ticket;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/customers")
class AgentCustomerController {

    private final AgentTicketService service;

    AgentCustomerController(AgentTicketService service) {
        this.service = service;
    }

    @GetMapping
    List<AgentCustomerSummary> list() {
        return service.customerSummaries();
    }
}
