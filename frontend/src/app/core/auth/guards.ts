import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { ROLE_HOME, UserRole } from './user-role';

// Guards are UX only and return a UrlTree; the backend enforces access (ADR-014 §5).
function zoneGuard(required: UserRole): CanMatchFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const user = auth.user();
    if (!user || !auth.hasValidSession()) {
      return router.createUrlTree(['/login']);
    }
    return user.role === required ? true : router.parseUrl(ROLE_HOME[user.role]);
  };
}

export const clientGuard: CanMatchFn = zoneGuard('CUSTOMER');
export const staffGuard: CanMatchFn = zoneGuard('AGENT');

export const guestGuard: CanMatchFn = () => {
  const auth = inject(AuthService);
  return auth.hasValidSession() ? auth.homeUrl() : true;
};
