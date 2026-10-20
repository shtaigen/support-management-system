package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.auth.CurrentUserId;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/customer/tickets")
class CustomerTicketController {

    private final CustomerTicketService service;

    CustomerTicketController(CustomerTicketService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<CustomerTicketResponse> create(@CurrentUserId long customerId,
                                                  @Valid @RequestBody CreateTicketRequest request) {
        CustomerTicketResponse ticket = service.create(customerId, request);
        return ResponseEntity.created(URI.create("/api/customer/tickets/" + ticket.id())).body(ticket);
    }

    @GetMapping("/{id}")
    CustomerTicketResponse get(@CurrentUserId long customerId, @PathVariable long id) {
        return service.get(customerId, id);
    }
}
