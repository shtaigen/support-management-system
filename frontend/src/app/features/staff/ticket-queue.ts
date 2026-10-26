import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButton],
  selector: 'app-ticket-queue',
  template: `
    <h1>Ticket queue</h1>
    <button mat-button (click)="auth.logout()">Log out</button>
  `,
  styles: `
    :host {
      display: block;
      padding: 24px;
    }
  `,
})
export class TicketQueue {
  protected readonly auth = inject(AuthService);
}
