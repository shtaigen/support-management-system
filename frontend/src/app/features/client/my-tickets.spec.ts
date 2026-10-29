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
import { CustomerTicketSummary } from './customer-ticket';
import { TICKETS_URL } from './customer-tickets-api';
import { MyTickets } from './my-tickets';

// Noon UTC: the same calendar date in every zone the tests run in (UTC, UTC+2).
const PRINTER: CustomerTicketSummary = {
  id: 42,
  subject: 'Printer broken',
  category: 'GENERAL',
  referenceNumber: 'ABC-1',
  status: 'ANSWERED',
  createdAt: '2026-10-19T12:00:00Z',
  lastMessageAt: '2026-10-20T12:00:00Z',
};
const CLAIM: CustomerTicketSummary = {
  id: 7,
  subject: 'Damaged parcel',
  category: 'CLAIM',
  referenceNumber: null,
  status: 'OPEN',
  createdAt: '2026-10-18T12:00:00Z',
  lastMessageAt: '2026-10-18T12:00:00Z',
};

describe('MyTickets', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'client', component: MyTickets }]),
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

  // Matches the path only; parameter checks live in the tests that are about them.
  function expectList(): TestRequest {
    return httpTesting.expectOne((r) => r.method === 'GET' && r.url === TICKETS_URL);
  }

  async function open(): Promise<void> {
    await harness.navigateByUrl('/client', MyTickets);
  }

  async function flush(req: TestRequest, tickets: CustomerTicketSummary[]): Promise<void> {
    req.flush(tickets);
    await harness.fixture.whenStable();
  }

  async function selectFilter(label: string): Promise<void> {
    const button = [...root().querySelectorAll('mat-button-toggle button')].find(
      (b) => b.textContent!.trim() === label,
    ) as HTMLButtonElement;
    button.click();
    await harness.fixture.whenStable();
  }

  function spinner(): Element | null {
    return root().querySelector('mat-progress-spinner');
  }

  function rows(): HTMLAnchorElement[] {
    return [...root().querySelectorAll<HTMLAnchorElement>('mat-nav-list a')];
  }

  it('loads once without a status parameter and shows the spinner until the response', async () => {
    await open();
    const req = expectList();
    expect(req.request.params.keys()).toEqual([]);
    expect(spinner()).not.toBeNull();

    await flush(req, [PRINTER]);
    expect(spinner()).toBeNull();
    expect(rows().length).toBe(1);
  });

  it('labels the list spinner Loading tickets', async () => {
    await open();
    const req = expectList();
    expect(spinner()!.getAttribute('aria-label')).toBe('Loading tickets');
    await flush(req, []);
  });

  it('shows the fields of each row in server order and links to the thread', async () => {
    await open();
    await flush(expectList(), [PRINTER, CLAIM]);

    const [first, second] = rows();
    expect(rows().length).toBe(2);
    expect(first.textContent).toContain('Printer broken');
    expect(first.textContent).toContain('General');
    expect(first.querySelector('.reference')!.textContent!.trim()).toBe('Ref. ABC-1');
    expect(first.textContent).toContain('Answered');
    // The date only: the hour depends on the machine time zone.
    expect(first.textContent).toContain('Oct 20, 2026');
    expect(first.getAttribute('href')).toBe('/client/tickets/42');
    expect(second.textContent).toContain('Damaged parcel');
    expect(second.getAttribute('href')).toBe('/client/tickets/7');
  });

  it('renders nothing for a null reference number', async () => {
    await open();
    await flush(expectList(), [CLAIM]);

    const [row] = rows();
    expect(row.querySelector('.reference')).toBeNull();
    expect(row.textContent).not.toContain('null');
    expect(row.textContent).not.toContain('Ref.');
  });

  it('sends the selected status', async () => {
    await open();
    await flush(expectList(), [PRINTER]);

    await selectFilter('Open');
    const req = expectList();
    expect(req.request.params.getAll('status')).toEqual(['OPEN']);
    await flush(req, [CLAIM]);
  });

  it('sends no status parameter after selecting All again', async () => {
    await open();
    await flush(expectList(), [PRINTER]);
    await selectFilter('Open');
    await flush(expectList(), [CLAIM]);

    await selectFilter('All');
    const req = expectList();
    expect(req.request.params.keys()).toEqual([]);
    await flush(req, [PRINTER, CLAIM]);
  });

  it('cancels the pending request when the filter changes', async () => {
    await open();
    await flush(expectList(), [PRINTER]);

    await selectFilter('Open');
    const pending = expectList();
    await selectFilter('Closed');
    const current = expectList();

    expect(pending.cancelled).toBe(true);
    expect(current.request.params.getAll('status')).toEqual(['CLOSED']);
    await flush(current, []);
  });

  it("hides the previous filter's rows while the new request is pending", async () => {
    await open();
    await flush(expectList(), [PRINTER]);

    await selectFilter('Open');
    const req = expectList();
    expect(root().textContent).not.toContain('Printer broken');
    expect(spinner()).not.toBeNull();
    await flush(req, [CLAIM]);
  });

  it('shows "No tickets yet" with a New ticket action for an empty list', async () => {
    await open();
    await flush(expectList(), []);

    const empty = root().querySelector('.empty')!;
    expect(empty.textContent).toContain('No tickets yet');
    const link = empty.querySelector('a')!;
    expect(link.textContent!.trim()).toBe('New ticket');
    expect(link.getAttribute('href')).toBe('/client/tickets/new');
  });

  it('shows "No tickets with this status" for an empty filtered list', async () => {
    await open();
    await flush(expectList(), [PRINTER]);
    await selectFilter('Closed');
    await flush(expectList(), []);

    expect(root().textContent).toContain('No tickets with this status');
    expect(root().textContent).not.toContain('No tickets yet');
  });

  it('shows the error detail and retries with the current filter', async () => {
    await open();
    await flush(expectList(), [PRINTER]);
    await selectFilter('Open');
    expectList().flush(
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
    const req = expectList();
    expect(req.request.params.getAll('status')).toEqual(['OPEN']);
    await flush(req, [CLAIM]);
    expect(rows().map((row) => row.getAttribute('href'))).toEqual(['/client/tickets/7']);
  });

  it('sends no request when the already selected filter is clicked', async () => {
    await open();
    await flush(expectList(), [PRINTER]);

    await selectFilter('All');
    httpTesting.expectNone((r) => r.url === TICKETS_URL);
  });
});
