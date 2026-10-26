import { InjectionToken } from '@angular/core';
import { UserRole } from './user-role';

// The login response (FR-01), stored as is (ADR-014 §1).
export interface Session {
  accessToken: string;
  expiresAt: string;
  user: { id: number; name: string; role: UserRole };
}

export const SESSION_KEY = 'sms.session';

export const SESSION_STORAGE = new InjectionToken<Storage>('SESSION_STORAGE', {
  providedIn: 'root',
  factory: () => sessionStorage,
});
