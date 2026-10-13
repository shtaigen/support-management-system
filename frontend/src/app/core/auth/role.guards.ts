import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { ROLE_HOME, UserRole } from './user-role';

function roleGuard(required: UserRole): CanMatchFn {
  return () => {
    const current = inject(AuthService).role();
    const router = inject(Router);
    if (current === null) {
      return router.createUrlTree(['/login']);
    }
    if (current === required) {
      return true;
    }
    return router.parseUrl(ROLE_HOME[current]);
  };
}

export const clientGuard: CanMatchFn = roleGuard('CUSTOMER');
export const staffGuard: CanMatchFn = roleGuard('AGENT');
