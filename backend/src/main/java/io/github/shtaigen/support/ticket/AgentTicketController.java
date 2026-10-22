package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.auth.CurrentUserId;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/tickets")
class AgentTicketController {

    private final AgentTicketService service;

    AgentTicketController(AgentTicketService service) {
        this.service = service;
    }

    @GetMapping
    List<AgentTicketSummary> list(@RequestParam(name = "status", required = false) List<TicketStatus> status,
                                  @RequestParam(name = "assigneeId", required = false) Long assigneeId,
                                  @RequestParam(name = "unassigned", required = false) Boolean unassigned,
                                  @RequestParam(name = "customerId", required = false) Long customerId) {
        return service.list(status, assigneeId, unassigned, customerId);
    }

    @GetMapping("/{id}")
    AgentTicketResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/{id}/messages")
    AgentTicketResponse postMessage(@CurrentUserId long agentId, @PathVariable long id,
                                    @Valid @RequestBody PostMessageRequest request) {
        return service.postMessage(agentId, id, request);
    }
}
