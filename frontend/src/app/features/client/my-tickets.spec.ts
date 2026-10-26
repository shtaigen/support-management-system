import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/auth/auth.service';
import { MyTickets } from './my-tickets';

describe('MyTickets', () => {
  it('logs out from the Log out button', async () => {
    const logout = vi.fn();
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { logout } }] });
    const fixture = TestBed.createComponent(MyTickets);
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    expect(logout).toHaveBeenCalledExactlyOnceWith();
  });
});
