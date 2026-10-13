import { TestBed } from '@angular/core/testing';
import { CanMatchFn, Route, Router, UrlTree, provideRouter } from '@angular/router';
import { AuthService } from './auth.service';
import { clientGuard, staffGuard } from './role.guards';
import { UserRole } from './user-role';

describe('role guards', () => {
  let auth: AuthService;
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    auth = TestBed.inject(AuthService);
    router = TestBed.inject(Router);
  });

  function run(guard: CanMatchFn, role: UserRole | null) {
    if (role) {
      auth.loginAs(role);
    }
    const snapshot = {} as Parameters<CanMatchFn>[2];
    return TestBed.runInInjectionContext(() => guard({} as Route, [], snapshot));
  }

  function expectRedirect(result: unknown, url: string) {
    expect(result).toBeInstanceOf(UrlTree);
    expect(router.serializeUrl(result as UrlTree)).toBe(url);
  }

  describe('clientGuard', () => {
    it('allows CUSTOMER', () => {
      expect(run(clientGuard, 'CUSTOMER')).toBe(true);
    });

    it('redirects AGENT to their own zone', () => {
      expectRedirect(run(clientGuard, 'AGENT'), '/staff');
    });

    it('redirects anonymous user to /login', () => {
      expectRedirect(run(clientGuard, null), '/login');
    });
  });

  describe('staffGuard', () => {
    it('allows AGENT', () => {
      expect(run(staffGuard, 'AGENT')).toBe(true);
    });

    it('redirects CUSTOMER to their own zone', () => {
      expectRedirect(run(staffGuard, 'CUSTOMER'), '/client');
    });

    it('redirects anonymous user to /login', () => {
      expectRedirect(run(staffGuard, null), '/login');
    });
  });
});
