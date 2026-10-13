import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-ticket-queue',
  template: `<h1>Ticket queue</h1>`,
  styles: `:host { display: block; padding: 24px; }`,
})
export class TicketQueue {}
