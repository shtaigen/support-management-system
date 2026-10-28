import { TicketCategory, TicketStatus } from './customer-ticket';

// The one place for display labels in the customer zone (ADR-015 §8).
export const STATUS_LABELS: Record<TicketStatus, string> = {
  OPEN: 'Open',
  ANSWERED: 'Answered',
  CUSTOMER_REPLIED: 'Customer replied',
  CLOSED: 'Closed',
};

export const CATEGORY_LABELS: Record<TicketCategory, string> = {
  GENERAL: 'General',
  POLICY: 'Policy',
  CLAIM: 'Claim',
};
