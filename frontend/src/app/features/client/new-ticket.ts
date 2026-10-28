import { ChangeDetectionStrategy, Component } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { RouterLink } from '@angular/router';

// Placeholder; the form (FR-03) comes in a later step.
@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButton, RouterLink],
  selector: 'app-new-ticket',
  template: `
    <h1>New ticket</h1>
    <a mat-button routerLink="/client">Back to my tickets</a>
  `,
  styles: `
    :host {
      display: block;
      padding: 24px;
    }
  `,
})
export class NewTicket {}
