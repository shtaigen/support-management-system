import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService, LOGIN_URL } from './auth.service';
import { MemoryStorage } from './memory-storage';
import { SESSION_KEY, SESSION_STORAGE, Session } from './session';

const NOW = Date.parse('2026-10-25T12:00:00Z');
const IN_ONE_HOUR = '2026-10-25T13:00:00Z';

function session(overrides: Partial<Session> = {}): Session {
  return {
    accessToken: 'token-1',
    expiresAt: IN_ONE_HOUR,
    user: { id: 1, name: 'Alice Agent', role: 'AGENT' },
    ...overrides,
  };
}

describe('AuthService', () => {
  let storage: MemoryStorage;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
    storage = new MemoryStorage();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: storage },
      ],
    });
  });

  afterEach(() => {
    TestBed.inject(HttpTestingController).verify();
    vi.useRealTimers();
  });

  function restore(raw: string): AuthService {
    storage.setItem(SESSION_KEY, raw);
    return TestBed.inject(AuthService);
  }

  function expectRejected(service: AuthService) {
    expect(service.session()).toBeNull();
    expect(service.hasValidSession()).toBe(false);
    expect(storage.getItem(SESSION_KEY)).toBeNull();
  }

  function logIn(service: AuthService, value = session()) {
    service.login('alice@example.com', 'secret').subscribe();
    TestBed.inject(HttpTestingController).expectOne(LOGIN_URL).flush(value);
  }

  describe('restore', () => {
    it('restores a valid session synchronously in the constructor', () => {
      const service = restore(JSON.stringify(session()));
      expect(service.hasValidSession()).toBe(true);
      expect(service.user()).toEqual({ id: 1, name: 'Alice Agent', role: 'AGENT' });
    });

    it('removes an expired session', () => {
      expectRejected(restore(JSON.stringify(session({ expiresAt: '2026-10-25T11:59:59Z' }))));
    });

    it('removes corrupt JSON', () => {
      expectRejected(restore('{not json'));
    });

    it('removes a session with an unknown role', () => {
      const user = { id: 1, name: 'Mallory', role: 'ADMIN' } as unknown as Session['user'];
      expectRejected(restore(JSON.stringify(session({ user }))));
    });

    it('removes a session with a missing field', () => {
      const user = { name: 'Alice Agent', role: 'AGENT' } as unknown as Session['user'];
      expectRejected(restore(JSON.stringify(session({ user }))));
    });

    it('removes a session with an unparsable expiresAt', () => {
      expectRejected(restore(JSON.stringify(session({ expiresAt: 'tomorrow' }))));
    });

    it('starts without a session when nothing is stored', () => {
      const service = TestBed.inject(AuthService);
      expect(service.session()).toBeNull();
      expect(service.hasValidSession()).toBe(false);
    });
  });

  describe('login', () => {
    it('posts the credentials and stores the session', () => {
      const service = TestBed.inject(AuthService);
      let result: Session | undefined;
      service.login('alice@example.com', 'secret').subscribe((s) => (result = s));

      const req = TestBed.inject(HttpTestingController).expectOne(LOGIN_URL);
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ email: 'alice@example.com', password: 'secret' });
      req.flush(session());

      expect(result).toEqual(session());
      expect(service.session()).toEqual(session());
      expect(JSON.parse(storage.getItem(SESSION_KEY)!)).toEqual(session());
    });

    it('a session that expires after login is no longer valid', () => {
      const service = TestBed.inject(AuthService);
      logIn(service);
      // Read once before the clock moves, so a computed would already be cached as true.
      expect(service.hasValidSession()).toBe(true);

      vi.setSystemTime(NOW + 61 * 60_000);
      expect(service.hasValidSession()).toBe(false);
    });
  });

  describe('logout', () => {
    let service: AuthService;
    let navigate: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
      service = restore(JSON.stringify(session()));
      navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    });

    it('clears the session and navigates to /login', () => {
      service.logout();
      expectRejected(service);
      expect(navigate).toHaveBeenCalledExactlyOnceWith('/login');
    });

    it('a second logout does not navigate', () => {
      service.logout();
      service.logout();
      expect(navigate).toHaveBeenCalledOnce();
    });

    it('navigates to /login?expired=1 when expired', () => {
      service.logout({ expired: true });
      expect(navigate).toHaveBeenCalledExactlyOnceWith('/login?expired=1');
    });
  });
});
