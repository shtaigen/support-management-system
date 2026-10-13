import { Injectable, computed, signal } from '@angular/core';
import { UserRole } from './user-role';

// TODO: temporary stub. Replace with real authentication (backend login,
// session/token handling) once it exists. The role is kept in memory only.
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly _role = signal<UserRole | null>(null);

  readonly role = this._role.asReadonly();
  readonly isLoggedIn = computed(() => this._role() !== null);

  loginAs(role: UserRole): void {
    this._role.set(role);
  }

  logout(): void {
    this._role.set(null);
  }
}
