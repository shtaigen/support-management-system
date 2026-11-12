import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { StaffShell } from './staff-shell';

describe('StaffShell', () => {
  const logout = vi.fn();

  beforeEach(() => {
    logout.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            user: signal({ id: 2, name: 'Alice Agent', role: 'AGENT' }),
            logout,
          },
        },
      ],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(StaffShell);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('links to the queue and the customer summary', async () => {
    const root = await render();
    const links = [...root.querySelectorAll('nav a')].map((a) => [
      a.textContent!.trim(),
      a.getAttribute('href'),
    ]);
    expect(links).toEqual([
      ['Queue', '/staff'],
      ['Customers', '/staff/customers'],
    ]);
  });

  it("shows the user's name", async () => {
    const root = await render();
    expect(root.querySelector('nav')!.textContent).toContain('Alice Agent');
  });

  it('logs out from the Log out button without arguments', async () => {
    const root = await render();
    const button = [...root.querySelectorAll('button')].find(
      (b) => b.textContent!.trim() === 'Log out',
    )!;
    button.click();
    expect(logout).toHaveBeenCalledExactlyOnceWith();
  });
});
