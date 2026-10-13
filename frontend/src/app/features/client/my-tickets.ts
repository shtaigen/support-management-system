import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  selector: 'app-my-tickets',
  template: `<h1>My tickets</h1>`,
  styles: `:host { display: block; padding: 24px; }`,
})
export class MyTickets {}
