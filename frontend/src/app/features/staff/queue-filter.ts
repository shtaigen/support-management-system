import { TicketStatus } from '../../shared/ticket/ticket';
import { AgentTicketFilter } from './agent-ticket';

// String keys, never null: mat-select treats a null option as a reset (ADR-016 §2).
export type StatusKey = 'active' | 'all' | TicketStatus;
export type AssigneeKey = 'anyone' | 'unassigned' | `agent:${number}`;

// Every status except CLOSED.
export const ACTIVE_STATUSES: readonly TicketStatus[] = ['OPEN', 'ANSWERED', 'CUSTOMER_REPLIED'];

export function toTicketFilter(
  status: StatusKey,
  assignee: AssigneeKey,
  customerId?: string,
): AgentTicketFilter {
  const filter: AgentTicketFilter = { statuses: toStatuses(status) };
  if (assignee === 'unassigned') {
    filter.unassigned = true;
  } else if (assignee !== 'anyone') {
    filter.assigneeId = Number(assignee.slice('agent:'.length));
  }
  if (customerId !== undefined) {
    filter.customerId = customerId;
  }
  return filter;
}

function toStatuses(status: StatusKey): TicketStatus[] {
  switch (status) {
    case 'active':
      return [...ACTIVE_STATUSES];
    case 'all':
      return [];
    default:
      return [status];
  }
}
