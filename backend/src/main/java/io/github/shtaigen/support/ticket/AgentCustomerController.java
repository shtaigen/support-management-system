package io.github.shtaigen.support.ticket;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/agent/customers")
@Tag(name = "Agent customers")
class AgentCustomerController {

    private final AgentTicketService service;

    AgentCustomerController(AgentTicketService service) {
        this.service = service;
    }

    // Declared responses replace the generated ones; the bare 200 keeps the schema generated from
    // the return type.
    @GetMapping
    @Operation(summary = "FR-08: View tickets grouped by customer")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "401", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    List<AgentCustomerSummary> list() {
        return service.customerSummaries();
    }
}
