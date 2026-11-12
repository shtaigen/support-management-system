import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButton, RouterLink, RouterOutlet],
  selector: 'app-staff-shell',
  template: `
    <nav aria-label="Agent zone">
      <a mat-button routerLink="/staff">Queue</a>
      <a mat-button routerLink="/staff/customers">Customers</a>
      <span class="spacer"></span>
      <span class="user">{{ auth.user()?.name }}</span>
      <button mat-button type="button" (click)="auth.logout()">Log out</button>
    </nav>
    <router-outlet />
  `,
  styles: `
    :host {
      display: block;
    }
    nav {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 8px 24px;
      border-bottom: 1px solid var(--mat-sys-outline-variant);
    }
    .spacer {
      flex: 1;
    }
    .user {
      color: var(--mat-sys-on-surface-variant);
    }
  `,
})
export class StaffShell {
  protected readonly auth = inject(AuthService);
}
