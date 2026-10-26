import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router, UrlTree } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { SESSION_KEY, SESSION_STORAGE, Session } from './session';
import { ROLE_HOME, isUserRole } from './user-role';

export const LOGIN_URL = '/api/auth/login';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly storage = inject(SESSION_STORAGE);

  private readonly _session = signal<Session | null>(null);

  readonly session = this._session.asReadonly();
  readonly user = computed(() => this._session()?.user ?? null);

  constructor() {
    // Synchronous, so guards see the session on the first navigation (ADR-014 §8).
    const restored = this.read();
    if (restored && isLive(restored)) {
      this._session.set(restored);
    } else {
      this.storage.removeItem(SESSION_KEY);
    }
  }

  // A method, not a computed: signals do not track time (ADR-014 §2).
  hasValidSession(): boolean {
    const session = this._session();
    return session !== null && isLive(session);
  }

  homeUrl(): UrlTree {
    const user = this.user();
    return this.router.parseUrl(user && this.hasValidSession() ? ROLE_HOME[user.role] : '/login');
  }

  login(email: string, password: string): Observable<Session> {
    return this.http.post<Session>(LOGIN_URL, { email, password }).pipe(
      tap((session) => {
        this.storage.setItem(SESSION_KEY, JSON.stringify(session));
        this._session.set(session);
      }),
    );
  }

  // Idempotent: parallel 401s cause one navigation (ADR-014 §3).
  logout(options?: { expired?: boolean }): void {
    if (this._session() === null) {
      return;
    }
    this.storage.removeItem(SESSION_KEY);
    this._session.set(null);
    void this.router.navigateByUrl(options?.expired ? '/login?expired=1' : '/login');
  }

  private read(): Session | null {
    try {
      const raw = this.storage.getItem(SESSION_KEY);
      return raw === null ? null : asSession(JSON.parse(raw));
    } catch {
      return null;
    }
  }
}

// An unparsable expiresAt gives NaN, which is never greater than now.
function isLive(session: Session): boolean {
  return Date.now() < Date.parse(session.expiresAt);
}

function asSession(value: unknown): Session | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const { accessToken, expiresAt, user } = value as Record<string, unknown>;
  if (typeof accessToken !== 'string' || accessToken === '' || typeof expiresAt !== 'string') {
    return null;
  }
  if (typeof user !== 'object' || user === null) {
    return null;
  }
  const { id, name, role } = user as Record<string, unknown>;
  if (typeof id !== 'number' || typeof name !== 'string' || !isUserRole(role)) {
    return null;
  }
  return { accessToken, expiresAt, user: { id, name, role } };
}
