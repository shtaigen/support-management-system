import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService, LOGIN_URL } from '../auth/auth.service';

// Any 401 except from login ends the session; 403 never does (ADR-014 §3).
export const unauthorizedInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && req.url !== LOGIN_URL) {
        auth.logout({ expired: true });
      }
      return throwError(() => error);
    }),
  );
};
