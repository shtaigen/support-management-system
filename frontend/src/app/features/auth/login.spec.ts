import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { LOGIN_URL } from '../../core/auth/auth.service';
import { MemoryStorage } from '../../core/auth/memory-storage';
import { SESSION_STORAGE } from '../../core/auth/session';
import { Login } from './login';

const EXPIRED_MESSAGE = 'Your session has expired. Please sign in again.';

describe('Login', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'login', component: Login }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    harness = await RouterTestingHarness.create();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpTesting.verify();
  });

  async function open(url = '/login'): Promise<Login> {
    return harness.navigateByUrl(url, Login);
  }

  function fill(email: string, password: string) {
    const root = harness.routeNativeElement!;
    for (const [selector, value] of [
      ['input[type=email]', email],
      ['input[type=password]', password],
    ]) {
      const input = root.querySelector<HTMLInputElement>(selector)!;
      input.value = value;
      input.dispatchEvent(new Event('input'));
    }
  }

  function alerts(): string[] {
    return [...harness.routeNativeElement!.querySelectorAll('[role=alert]')].map((el) =>
      el.textContent!.trim(),
    );
  }

  it('shows the detail of a 401', async () => {
    const login = await open();
    fill('alice@example.com', 'wrong');
    login.submit();
    httpTesting
      .expectOne(LOGIN_URL)
      .flush(
        { status: 401, title: 'Unauthorized', detail: 'Invalid email or password.' },
        { status: 401, statusText: 'Unauthorized' },
      );
    await harness.fixture.whenStable();
    expect(alerts()).toEqual(['Invalid email or password.']);
  });

  it('sends one request on a double submit', async () => {
    const login = await open();
    fill('alice@example.com', 'secret');
    // Direct calls, not clicks: the disabled button would hide a missing guard.
    login.submit();
    login.submit();
    httpTesting
      .expectOne(LOGIN_URL)
      .flush(
        { status: 401, title: 'Unauthorized', detail: 'Invalid email or password.' },
        { status: 401, statusText: 'Unauthorized' },
      );
  });

  it('navigates to the user zone with replaceUrl on success', async () => {
    const login = await open();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    fill('alice@example.com', 'secret');
    login.submit();
    httpTesting.expectOne(LOGIN_URL).flush({
      accessToken: 'token',
      expiresAt: '2999-01-01T00:00:00Z',
      user: { id: 1, name: 'Alice Agent', role: 'AGENT' },
    });
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/staff', { replaceUrl: true });
  });

  it('shows the expired message until the first submit', async () => {
    const login = await open('/login?expired=1');
    expect(alerts()).toEqual([EXPIRED_MESSAGE]);

    login.submit(); // the empty form is invalid, so no request is sent
    await harness.fixture.whenStable();
    expect(alerts()).toEqual([]);
  });

  it('does not show the expired message without the query parameter', async () => {
    await open();
    expect(alerts()).toEqual([]);
  });
});
