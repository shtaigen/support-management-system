import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ClientShell } from './client-shell';

describe('ClientShell', () => {
  const logout = vi.fn();

  beforeEach(() => {
    logout.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: {
            user: signal({ id: 1, name: 'Carol Customer', role: 'CUSTOMER' }),
            logout,
          },
        },
      ],
    });
  });

  async function render(): Promise<HTMLElement> {
    const fixture = TestBed.createComponent(ClientShell);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it("shows the user's name", async () => {
    const root = await render();
    expect(root.querySelector('nav')!.textContent).toContain('Carol Customer');
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
