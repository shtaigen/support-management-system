import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { CanMatchFn, Route, Router, UrlTree, provideRouter } from '@angular/router';
import { clientGuard, guestGuard, staffGuard } from './guards';
import { MemoryStorage } from './memory-storage';
import { SESSION_KEY, SESSION_STORAGE } from './session';
import { UserRole } from './user-role';

const NOW = Date.parse('2026-10-25T12:00:00Z');

describe('guards', () => {
  let storage: MemoryStorage;

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(NOW);
    storage = new MemoryStorage();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        { provide: SESSION_STORAGE, useValue: storage },
      ],
    });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  // Seeds storage before AuthService is created, so the constructor restores it.
  function signIn(role: UserRole) {
    storage.setItem(
      SESSION_KEY,
      JSON.stringify({
        accessToken: 'token',
        expiresAt: '2026-10-25T13:00:00Z',
        user: { id: 1, name: 'User', role },
      }),
    );
  }

  function run(guard: CanMatchFn) {
    const snapshot = {} as Parameters<CanMatchFn>[2];
    return TestBed.runInInjectionContext(() => guard({} as Route, [], snapshot));
  }

  function expectRedirect(result: unknown, url: string) {
    expect(result).toBeInstanceOf(UrlTree);
    expect(TestBed.inject(Router).serializeUrl(result as UrlTree)).toBe(url);
  }

  describe('zone guards', () => {
    it('redirects to /login without a session', () => {
      expectRedirect(run(clientGuard), '/login');
      expectRedirect(run(staffGuard), '/login');
    });

    it('redirects to /login when the session has expired', () => {
      signIn('CUSTOMER');
      run(clientGuard); // creates AuthService while the session is still valid
      vi.setSystemTime(NOW + 61 * 60_000);
      expectRedirect(run(clientGuard), '/login');
    });

    it('allows CUSTOMER on /client', () => {
      signIn('CUSTOMER');
      expect(run(clientGuard)).toBe(true);
    });

    it('allows AGENT on /staff', () => {
      signIn('AGENT');
      expect(run(staffGuard)).toBe(true);
    });

    it('redirects AGENT from /client to /staff', () => {
      signIn('AGENT');
      expectRedirect(run(clientGuard), '/staff');
    });

    it('redirects CUSTOMER from /staff to /client', () => {
      signIn('CUSTOMER');
      expectRedirect(run(staffGuard), '/client');
    });
  });

  describe('guestGuard', () => {
    it('allows /login without a session', () => {
      expect(run(guestGuard)).toBe(true);
    });

    it('redirects a signed-in user to their own zone', () => {
      signIn('AGENT');
      expectRedirect(run(guestGuard), '/staff');
    });
  });
});
