package io.github.shtaigen.support.ticket;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The {@code status} list filter shared by FR-04 and FR-07. Spring MVC already splits the comma
 * form, converts the values case-sensitively and turns empty values into null; this class only
 * drops the nulls.
 */
final class TicketStatusFilter {

    private TicketStatusFilter() {
    }

    /**
     * @param statuses the converted query values; null, or null elements, for empty values
     * @return the statuses to filter by; empty means all statuses
     */
    static Set<TicketStatus> of(@Nullable Collection<TicketStatus> statuses) {
        Set<TicketStatus> filter = EnumSet.noneOf(TicketStatus.class);
        if (statuses != null) {
            statuses.stream().filter(Objects::nonNull).forEach(filter::add);
        }
        return filter;
    }
}
