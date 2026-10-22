package io.github.shtaigen.support.ticket;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class TicketStatusFilterTest {

    @Test
    void absentParameterMeansAllStatuses() {
        assertThat(TicketStatusFilter.of(null)).isEmpty();
    }

    @Test
    void onlyEmptyValuesMeanAllStatuses() {
        assertThat(TicketStatusFilter.of(Arrays.asList(null, null))).isEmpty();
    }

    @Test
    void emptyValuesAndDuplicatesAreDropped() {
        assertThat(TicketStatusFilter.of(Arrays.asList(TicketStatus.CLOSED, null, TicketStatus.OPEN, TicketStatus.CLOSED)))
                .containsExactlyInAnyOrder(TicketStatus.OPEN, TicketStatus.CLOSED);
    }
}
