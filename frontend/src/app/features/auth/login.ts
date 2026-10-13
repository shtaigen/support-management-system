import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButton } from '@angular/material/button';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ROLE_HOME, UserRole } from '../../core/auth/user-role';

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButton],
  selector: 'app-login',
  template: `
    <h1>Sign in</h1>
    <div class="actions">
      <button mat-flat-button (click)="enter('CUSTOMER')">Enter as client</button>
      <button mat-flat-button (click)="enter('AGENT')">Enter as staff</button>
    </div>
  `,
  styles: `
    :host { display: block; padding: 24px; }
    .actions { display: flex; gap: 16px; }
  `,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  enter(role: UserRole): void {
    this.auth.loginAs(role);
    void this.router.navigateByUrl(ROLE_HOME[role]);
  }
}
