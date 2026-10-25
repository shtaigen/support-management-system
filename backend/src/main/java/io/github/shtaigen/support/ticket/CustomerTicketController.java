package io.github.shtaigen.support.ticket;

import io.github.shtaigen.support.auth.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/customer/tickets")
@Tag(name = "Customer tickets")
class CustomerTicketController {

    private final CustomerTicketService service;

    CustomerTicketController(CustomerTicketService service) {
        this.service = service;
    }

    // Declared responses replace the generated ones; a bare success response keeps the schema
    // generated from the return type. The 201 of ResponseEntity.created is invisible to springdoc.
    @PostMapping
    @Operation(summary = "FR-03: Create a ticket")
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<CustomerTicketResponse> create(@CurrentUserId long customerId,
                                                  @Valid @RequestBody CreateTicketRequest request) {
        CustomerTicketResponse ticket = service.create(customerId, request);
        return ResponseEntity.created(URI.create("/api/customer/tickets/" + ticket.id())).body(ticket);
    }

    @GetMapping
    @Operation(summary = "FR-04: List own tickets")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    List<CustomerTicketSummary> list(@CurrentUserId long customerId,
                                     @RequestParam(name = "status", required = false) List<TicketStatus> status) {
        return service.list(customerId, status);
    }

    @GetMapping("/{id}")
    @Operation(summary = "FR-05: View an own ticket")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    CustomerTicketResponse get(@CurrentUserId long customerId, @PathVariable long id) {
        return service.get(customerId, id);
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "FR-06: Post a message as a customer")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    CustomerTicketResponse postMessage(@CurrentUserId long customerId, @PathVariable long id,
                                       @Valid @RequestBody PostMessageRequest request) {
        return service.postMessage(customerId, id, request);
    }
}
