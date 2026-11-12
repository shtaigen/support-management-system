import { Routes } from '@angular/router';
import { AgentTicketThread } from './agent-ticket-thread';
import { CustomerSummary } from './customer-summary';
import { StaffShell } from './staff-shell';
import { TicketQueue } from './ticket-queue';

export default [
  {
    path: '',
    component: StaffShell,
    children: [
      { path: '', component: TicketQueue, title: 'Ticket queue' },
      { path: 'customers', component: CustomerSummary, title: 'Customers' },
      // The same component, filtered by the customer (ADR-016 §2).
      { path: 'customers/:customerId', component: TicketQueue, title: 'Customer tickets' },
      { path: 'tickets/:id', component: AgentTicketThread, title: 'Ticket' },
    ],
  },
] satisfies Routes;
