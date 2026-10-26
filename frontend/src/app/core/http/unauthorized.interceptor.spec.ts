import {
  HttpClient,
  HttpErrorResponse,
  HttpHandlerFn,
  HttpRequest,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { throwError } from 'rxjs';
import { AuthService, LOGIN_URL } from '../auth/auth.service';
import { MemoryStorage } from '../auth/memory-storage';
import { SESSION_KEY, SESSION_STORAGE } from '../auth/session';
import { unauthorizedInterceptor } from './unauthorized.interceptor';

describe('unauthorizedInterceptor', () => {
  let auth: AuthService;
  let navigate: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    const storage = new MemoryStorage();
    storage.setItem(
      SESSION_KEY,
      JSON.stringify({
        accessToken: 'token',
        expiresAt: '2999-01-01T00:00:00Z',
        user: { id: 1, name: 'Carol Customer', role: 'CUSTOMER' },
      }),
    );
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([unauthorizedInterceptor])),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: storage },
      ],
    });
    auth = TestBed.inject(AuthService);
    navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  });

  // Runs the interceptor alone against a handler that fails with the given status.
  function failWith(status: number, url: string): void {
    const error = new HttpErrorResponse({ status, url });
    const next: HttpHandlerFn = () => throwError(() => error);
    let received: unknown;
    TestBed.runInInjectionContext(() =>
      unauthorizedInterceptor(new HttpRequest('GET', url), next),
    ).subscribe({ error: (e: unknown) => (received = e) });
    expect(received).toBe(error);
  }

  it('logs out as expired on 401 and rethrows the error', () => {
    failWith(401, '/api/customer/tickets');
    expect(auth.session()).toBeNull();
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/login?expired=1');
  });

  it('logs out on 401 from another /api/auth/ URL', () => {
    failWith(401, '/api/auth/session');
    expect(auth.session()).toBeNull();
  });

  it('does not log out on 401 from the login request', () => {
    failWith(401, LOGIN_URL);
    expect(auth.hasValidSession()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('does not log out on 403', () => {
    failWith(403, '/api/customer/tickets');
    expect(auth.hasValidSession()).toBe(true);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('navigates once when two in-flight requests both get 401', () => {
    const http = TestBed.inject(HttpClient);
    const httpTesting = TestBed.inject(HttpTestingController);
    http.get('/api/customer/tickets').subscribe({ error: () => undefined });
    http.get('/api/customer/tickets/1').subscribe({ error: () => undefined });

    const unauthorized = { status: 401, statusText: 'Unauthorized' };
    httpTesting.expectOne('/api/customer/tickets').flush(null, unauthorized);
    httpTesting.expectOne('/api/customer/tickets/1').flush(null, unauthorized);

    expect(navigate).toHaveBeenCalledExactlyOnceWith('/login?expired=1');
    httpTesting.verify();
  });
});
