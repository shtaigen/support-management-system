import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { AuthService, LOGIN_URL } from '../auth/auth.service';

// Only relative /api/ URLs get the token, never login or absolute URLs (ADR-014 §4).
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/') || req.url === LOGIN_URL) {
    return next(req);
  }
  const token = inject(AuthService).session()?.accessToken;
  return next(token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req);
};
