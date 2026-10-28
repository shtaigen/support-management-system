import { Routes } from '@angular/router';
import { ClientShell } from './client-shell';
import { MyTickets } from './my-tickets';
import { NewTicket } from './new-ticket';
import { TicketThread } from './ticket-thread';

export default [
  {
    path: '',
    component: ClientShell,
    children: [
      { path: '', component: MyTickets, title: 'My tickets' },
      // Before 'tickets/:id', which would otherwise match "new" as an id (ADR-015 §1).
      { path: 'tickets/new', component: NewTicket, title: 'New ticket' },
      { path: 'tickets/:id', component: TicketThread, title: 'Ticket' },
    ],
  },
] satisfies Routes;
