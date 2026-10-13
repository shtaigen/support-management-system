import { TestBed } from '@angular/core/testing';
import { AuthService } from './auth.service';

describe('AuthService', () => {
  let service: AuthService;

  beforeEach(() => {
    service = TestBed.inject(AuthService);
  });

  it('starts logged out', () => {
    expect(service.role()).toBeNull();
    expect(service.isLoggedIn()).toBe(false);
  });

  it('loginAs sets the role', () => {
    service.loginAs('CUSTOMER');
    expect(service.role()).toBe('CUSTOMER');
    expect(service.isLoggedIn()).toBe(true);

    service.loginAs('AGENT');
    expect(service.role()).toBe('AGENT');
  });

  it('logout clears the role', () => {
    service.loginAs('AGENT');
    service.logout();
    expect(service.role()).toBeNull();
    expect(service.isLoggedIn()).toBe(false);
  });
});
