import { UserRole } from '../../core/auth/user-role';
import { TicketCategory, TicketStatus } from '../../shared/ticket/ticket';

// Hand-written from the backend records and AgentTicketControllerTest (ADR-016 §2).
export interface AgentTicketSummary {
  id: number;
  subject: string;
  category: TicketCategory;
  referenceNumber: string | null;
  status: TicketStatus;
  customer: { id: number; name: string; email: string };
  // Always present; null for an unassigned ticket.
  assignee: { id: number; name: string } | null;
  lastMessageAt: string;
}

// Hand-written from the backend record AgentTicketResponse and a live check (ADR-016 §3).
export interface AgentTicketMessage {
  id: number;
  body: string;
  createdAt: string;
  // No id: the author is shown by name and role only.
  author: { name: string; role: UserRole };
}

export interface AgentTicketResponse extends AgentTicketSummary {
  createdAt: string;
  updatedAt: string;
  // Not used before step 8d (FR-11, FR-12).
  version: number;
  messages: AgentTicketMessage[];
}

export interface AgentCustomerSummary {
  id: number;
  name: string;
  email: string;
  ticketCount: number;
  activeTicketCount: number;
  lastMessageAt: string;
}

export interface AgentRef {
  id: number;
  name: string;
}

// customerId stays a string: a Java long can exceed Number.MAX_SAFE_INTEGER (ADR-015 §3).
export interface AgentTicketFilter {
  statuses: TicketStatus[];
  assigneeId?: number;
  unassigned?: boolean;
  customerId?: string;
}
