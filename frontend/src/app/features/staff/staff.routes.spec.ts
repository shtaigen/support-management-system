import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRouteSnapshot, Router, provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MemoryStorage } from '../../core/auth/memory-storage';
import { SESSION_STORAGE } from '../../core/auth/session';
import { AGENTS_URL, AGENT_CUSTOMERS_URL, AGENT_TICKETS_URL } from './agent-api';
import { AgentTicketThread } from './agent-ticket-thread';
import { CustomerSummary } from './customer-summary';
import { StaffShell } from './staff-shell';
import { TicketQueue } from './ticket-queue';

describe('staff routes', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'staff', loadChildren: () => import('./staff.routes') }]),
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

  // The route chain below the root, from the shell down to the page.
  function routeChain(): ActivatedRouteSnapshot[] {
    const chain: ActivatedRouteSnapshot[] = [];
    let route = TestBed.inject(Router).routerState.snapshot.root;
    while (route.firstChild) {
      route = route.firstChild;
      chain.push(route);
    }
    return chain;
  }

  function components(): unknown[] {
    return routeChain()
      .map((route) => route.component)
      .filter((component) => component !== undefined && component !== null);
  }

  function answerQueue(): void {
    httpTesting.expectOne((r) => r.url === AGENT_TICKETS_URL).flush([]);
    httpTesting.expectOne({ method: 'GET', url: AGENTS_URL }).flush([]);
  }

  it('resolves /staff to TicketQueue under StaffShell', async () => {
    await harness.navigateByUrl('/staff');
    expect(components()).toEqual([StaffShell, TicketQueue]);
    answerQueue();
  });

  it('resolves /staff/customers to CustomerSummary under StaffShell', async () => {
    await harness.navigateByUrl('/staff/customers');
    expect(components()).toEqual([StaffShell, CustomerSummary]);
    httpTesting.expectOne({ method: 'GET', url: AGENT_CUSTOMERS_URL }).flush([]);
  });

  it('resolves /staff/customers/42 to TicketQueue under StaffShell with the id as a string', async () => {
    await harness.navigateByUrl('/staff/customers/42');
    expect(components()).toEqual([StaffShell, TicketQueue]);
    expect(routeChain().at(-1)!.paramMap.get('customerId')).toBe('42');
    answerQueue();
  });

  it('resolves /staff/tickets/42 to AgentTicketThread under StaffShell with the id as a string', async () => {
    await harness.navigateByUrl('/staff/tickets/42');
    expect(components()).toEqual([StaffShell, AgentTicketThread]);
    expect(routeChain().at(-1)!.paramMap.get('id')).toBe('42');
    // 404 shows "Ticket not found", so polling never starts in this test.
    httpTesting
      .expectOne({ method: 'GET', url: `${AGENT_TICKETS_URL}/42` })
      .flush(
        { status: 404, title: 'Not Found', detail: 'Ticket not found.' },
        { status: 404, statusText: 'Not Found' },
      );
  });
});
