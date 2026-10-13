import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink],
  selector: 'app-not-found',
  template: `
    <h1>Page not found</h1>
    <a routerLink="/login">Go to login</a>
  `,
  styles: `:host { display: block; padding: 24px; }`,
})
export class NotFound {}
