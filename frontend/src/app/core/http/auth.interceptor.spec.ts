import {
  HttpClient,
  HttpHandlerFn,
  HttpRequest,
  HttpResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AuthService, LOGIN_URL } from '../auth/auth.service';
import { MemoryStorage } from '../auth/memory-storage';
import { SESSION_STORAGE, Session } from '../auth/session';
import { authInterceptor } from './auth.interceptor';
import { unauthorizedInterceptor } from './unauthorized.interceptor';

function session(accessToken: string): Session {
  return {
    accessToken,
    expiresAt: '2999-01-01T00:00:00Z',
    user: { id: 1, name: 'Carol Customer', role: 'CUSTOMER' },
  };
}

describe('authInterceptor', () => {
  let httpTesting: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  function logIn(accessToken: string) {
    TestBed.inject(AuthService).login('carol@example.com', 'secret').subscribe();
    httpTesting.expectOne(LOGIN_URL).flush(session(accessToken));
  }

  // Runs the interceptor alone and returns the request it passed on.
  function intercept(url: string): HttpRequest<unknown> {
    let forwarded: HttpRequest<unknown> | undefined;
    const next: HttpHandlerFn = (req) => {
      forwarded = req;
      return of(new HttpResponse());
    };
    TestBed.runInInjectionContext(() =>
      authInterceptor(new HttpRequest('GET', url), next),
    ).subscribe();
    return forwarded!;
  }

  it('adds the bearer token to /api/ URLs', () => {
    logIn('token-1');
    expect(intercept('/api/customer/tickets').headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('adds the bearer token to other /api/auth/ URLs', () => {
    logIn('token-1');
    expect(intercept('/api/auth/session').headers.get('Authorization')).toBe('Bearer token-1');
  });

  it('does not add the token to the login URL', () => {
    logIn('token-1');
    expect(intercept(LOGIN_URL).headers.has('Authorization')).toBe(false);
  });

  it('does not add the token to an absolute URL', () => {
    logIn('token-1');
    const req = intercept('https://other.example/api/customer/tickets');
    expect(req.headers.has('Authorization')).toBe(false);
  });

  it('sends the request without the header when there is no session', () => {
    expect(intercept('/api/customer/tickets').headers.has('Authorization')).toBe(false);
  });

  it('reads the token at request time', () => {
    logIn('token-1');
    intercept('/api/customer/tickets');
    logIn('token-2');
    expect(intercept('/api/customer/tickets').headers.get('Authorization')).toBe('Bearer token-2');
  });
});

describe('interceptor wiring', () => {
  it('sends the token through the real HttpClient chain', () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor, unauthorizedInterceptor])),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    const httpTesting = TestBed.inject(HttpTestingController);

    TestBed.inject(AuthService).login('carol@example.com', 'secret').subscribe();
    const login = httpTesting.expectOne(LOGIN_URL);
    expect(login.request.headers.has('Authorization')).toBe(false);
    login.flush(session('token-1'));

    TestBed.inject(HttpClient).get('/api/customer/tickets').subscribe();
    const req = httpTesting.expectOne('/api/customer/tickets');
    expect(req.request.headers.get('Authorization')).toBe('Bearer token-1');
    req.flush([]);
    httpTesting.verify();
  });
});
