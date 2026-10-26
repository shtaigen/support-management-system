import { TestBed } from '@angular/core/testing';
import { AuthService } from '../../core/auth/auth.service';
import { TicketQueue } from './ticket-queue';

describe('TicketQueue', () => {
  it('logs out from the Log out button', async () => {
    const logout = vi.fn();
    TestBed.configureTestingModule({ providers: [{ provide: AuthService, useValue: { logout } }] });
    const fixture = TestBed.createComponent(TicketQueue);
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    expect(logout).toHaveBeenCalledExactlyOnceWith();
  });
});
