import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { MemoryStorage } from '../../core/auth/memory-storage';
import { SESSION_STORAGE } from '../../core/auth/session';
import { AGENT_CUSTOMERS_URL } from './agent-api';
import { AgentCustomerSummary } from './agent-ticket';
import { CustomerSummary } from './customer-summary';

// Server order, deliberately not by name or id: the client never sorts (NFR-05).
// Noon UTC: the same calendar date in every zone the tests run in (UTC, UTC+2).
const DAVE: AgentCustomerSummary = {
  id: 101,
  name: 'Dave Customer',
  email: 'dave@example.com',
  ticketCount: 3,
  activeTicketCount: 1,
  lastMessageAt: '2026-10-20T12:00:00Z',
};
const CAROL: AgentCustomerSummary = {
  id: 100,
  name: 'Carol Customer',
  email: 'carol@example.com',
  ticketCount: 1,
  activeTicketCount: 0,
  lastMessageAt: '2026-10-18T12:00:00Z',
};

describe('CustomerSummary', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'staff/customers', component: CustomerSummary }]),
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

  function root(): HTMLElement {
    return harness.routeNativeElement!;
  }

  function expectCustomers(): TestRequest {
    return httpTesting.expectOne({ method: 'GET', url: AGENT_CUSTOMERS_URL });
  }

  async function open(): Promise<void> {
    await harness.navigateByUrl('/staff/customers', CustomerSummary);
  }

  async function flush(req: TestRequest, customers: AgentCustomerSummary[]): Promise<void> {
    req.flush(customers);
    await harness.fixture.whenStable();
  }

  function rows(): HTMLElement[] {
    return [...root().querySelectorAll<HTMLElement>('tr.mat-mdc-row')];
  }

  function cells(row: HTMLElement): string[] {
    return [...row.querySelectorAll('td')].map((td) => td.textContent!.trim());
  }

  it('shows the columns and the customers in server order', async () => {
    await open();
    await flush(expectCustomers(), [DAVE, CAROL]);

    const headers = [...root().querySelectorAll('th')].map((th) => th.textContent!.trim());
    expect(headers).toEqual(['Customer', 'Email', 'Tickets', 'Active', 'Last activity']);
    const [first, second] = rows();
    expect(rows().length).toBe(2);
    // The date only: the hour depends on the machine time zone.
    expect(cells(first).slice(0, 4)).toEqual(['Dave Customer', 'dave@example.com', '3', '1']);
    expect(cells(first)[4]).toContain('Oct 20, 2026');
    expect(cells(second).slice(0, 4)).toEqual(['Carol Customer', 'carol@example.com', '1', '0']);
  });

  it("links the name to the customer's tickets", async () => {
    await open();
    await flush(expectCustomers(), [DAVE, CAROL]);

    const links = rows().map((row) => row.querySelector('a')!);
    expect(links.map((a) => a.textContent!.trim())).toEqual(['Dave Customer', 'Carol Customer']);
    expect(links.map((a) => a.getAttribute('href'))).toEqual([
      '/staff/customers/101',
      '/staff/customers/100',
    ]);
  });

  it('labels the spinner Loading customers', async () => {
    await open();
    const req = expectCustomers();
    const spinner = root().querySelector('mat-progress-spinner')!;
    expect(spinner.getAttribute('aria-label')).toBe('Loading customers');
    await flush(req, []);
    expect(root().querySelector('mat-progress-spinner')).toBeNull();
  });

  it('shows "No customers with tickets yet" for an empty summary', async () => {
    await open();
    await flush(expectCustomers(), []);

    expect(root().querySelector('.empty')!.textContent!.trim()).toBe(
      'No customers with tickets yet',
    );
  });

  it('shows the error detail and retries', async () => {
    await open();
    expectCustomers().flush(
      { status: 500, title: 'Internal Server Error', detail: 'Database is down.' },
      { status: 500, statusText: 'Internal Server Error' },
    );
    await harness.fixture.whenStable();
    expect(root().querySelector('[role=alert]')!.textContent!.trim()).toBe('Database is down.');

    const retry = [...root().querySelectorAll('button')].find(
      (b) => b.textContent!.trim() === 'Retry',
    )!;
    retry.click();
    await harness.fixture.whenStable();
    await flush(expectCustomers(), [CAROL]);
    expect(rows().length).toBe(1);
  });
});
