import { provideHttpClient } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { RedirectFunction, Router, UrlTree, provideRouter } from '@angular/router';
import { homeRedirect } from './home-redirect';
import { MemoryStorage } from './memory-storage';
import { SESSION_KEY, SESSION_STORAGE } from './session';
import { UserRole } from './user-role';

const NOW = Date.parse('2026-10-25T12:00:00Z');

describe('homeRedirect', () => {
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

  function redirect(): string {
    const snapshot = {} as Parameters<RedirectFunction>[0];
    const result = TestBed.runInInjectionContext(() => homeRedirect(snapshot));
    expect(result).toBeInstanceOf(UrlTree);
    return TestBed.inject(Router).serializeUrl(result as UrlTree);
  }

  it('goes to /login without a session', () => {
    expect(redirect()).toBe('/login');
  });

  it('goes to /client for CUSTOMER', () => {
    signIn('CUSTOMER');
    expect(redirect()).toBe('/client');
  });

  it('goes to /staff for AGENT', () => {
    signIn('AGENT');
    expect(redirect()).toBe('/staff');
  });

  it('goes to /login when the session has expired', () => {
    signIn('AGENT');
    redirect();
    vi.setSystemTime(NOW + 61 * 60_000);
    expect(redirect()).toBe('/login');
  });
});
