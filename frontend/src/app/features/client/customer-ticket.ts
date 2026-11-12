import { UserRole } from '../../core/auth/user-role';
import { TicketCategory, TicketStatus } from '../../shared/ticket/ticket';

// Re-exported so the customer zone keeps its imports (ADR-016 §2).
export type { TicketCategory, TicketStatus };

// Hand-written from the OpenAPI spec and live responses (ADR-015 §2).

export interface CustomerTicketSummary {
  id: number;
  subject: string;
  category: TicketCategory;
  referenceNumber: string | null;
  status: TicketStatus;
  createdAt: string;
  lastMessageAt: string;
}

export interface CustomerTicketMessage {
  id: number;
  body: string;
  createdAt: string;
  authorRole: UserRole;
}

export interface CustomerTicketResponse extends CustomerTicketSummary {
  messages: CustomerTicketMessage[];
}

// Values are sent as typed; the server normalizes them (ADR-015 §5).
export interface CreateTicketRequest {
  subject: string;
  category: TicketCategory;
  referenceNumber: string;
  message: string;
}
