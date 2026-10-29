import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MemoryStorage } from '../../core/auth/memory-storage';
import { SESSION_STORAGE } from '../../core/auth/session';
import { TICKETS_URL } from './customer-tickets-api';
import { MyTickets } from './my-tickets';
import { NewTicket } from './new-ticket';
import { TicketThread } from './ticket-thread';

describe('client routes', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'client', loadChildren: () => import('./client.routes') }]),
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

  // The harness's own component check sees the shell in the first outlet, so walk the tree.
  function deepestRoute(): ActivatedRouteSnapshot {
    let route = TestBed.inject(Router).routerState.snapshot.root;
    while (route.firstChild) {
      route = route.firstChild;
    }
    return route;
  }

  it('resolves /client/tickets/new to NewTicket, not to TicketThread', async () => {
    await harness.navigateByUrl('/client/tickets/new');
    expect(deepestRoute().component).toBe(NewTicket);
  });

  it('resolves /client/tickets/42 to TicketThread with the id as a string', async () => {
    await harness.navigateByUrl('/client/tickets/42');
    expect(deepestRoute().component).toBe(TicketThread);
    expect(deepestRoute().paramMap.get('id')).toBe('42');
    // 404 shows "Ticket not found", so polling never starts in this test.
    httpTesting
      .expectOne({ method: 'GET', url: `${TICKETS_URL}/42` })
      .flush(
        { status: 404, title: 'Not Found', detail: 'Ticket not found.' },
        { status: 404, statusText: 'Not Found' },
      );
  });

  it('resolves /client to MyTickets', async () => {
    await harness.navigateByUrl('/client');
    expect(deepestRoute().component).toBe(MyTickets);
    httpTesting.expectOne({ method: 'GET', url: TICKETS_URL }).flush([]);
  });
});
