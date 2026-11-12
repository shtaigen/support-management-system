// Hand-written from the OpenAPI spec; shared by both zones (ADR-016 §2).
export type TicketStatus = 'OPEN' | 'ANSWERED' | 'CUSTOMER_REPLIED' | 'CLOSED';
export type TicketCategory = 'GENERAL' | 'POLICY' | 'CLAIM';
