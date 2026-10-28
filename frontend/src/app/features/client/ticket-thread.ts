import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { RouterLink } from '@angular/router';

// Placeholder; the thread and composer (FR-05, FR-06) come in a later step.
@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButton, RouterLink],
  selector: 'app-ticket-thread',
  template: `
    <h1>Ticket</h1>
    <a mat-button routerLink="/client">Back to my tickets</a>
  `,
  styles: `
    :host {
      display: block;
      padding: 24px;
    }
  `,
})
export class TicketThread {}
