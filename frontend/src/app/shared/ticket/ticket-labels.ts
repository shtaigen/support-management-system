import { TicketCategory, TicketStatus } from './ticket';

// The one place for display labels in both zones (ADR-015 §8, ADR-016 §2).
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
