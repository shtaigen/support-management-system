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
import { SESSION_KEY, SESSION_STORAGE, Session } from '../../core/auth/session';
import { AGENTS_URL, AGENT_TICKETS_URL } from './agent-api';
import { AgentRef, AgentTicketSummary } from './agent-ticket';
import { TicketQueue } from './ticket-queue';

const ACTIVE_URL = '/api/agent/tickets?status=OPEN&status=ANSWERED&status=CUSTOMER_REPLIED';
// Beyond Number.MAX_SAFE_INTEGER: a conversion to a number would send ...992.
const LARGE_ID = '9007199254740993';

// The caller is Alice (id 2).
const SESSION: Session = {
  accessToken: 'token',
  expiresAt: '2999-01-01T00:00:00Z',
  user: { id: 2, name: 'Alice Agent', role: 'AGENT' },
};

// Server order, deliberately not alphabetical: the client never sorts (NFR-05).
const AGENTS: AgentRef[] = [
  { id: 9, name: 'Zed Agent' },
  { id: 2, name: 'Alice Agent' },
  { id: 5, name: 'Bob Agent' },
];

// Noon UTC: the same calendar date in every zone the tests run in (UTC, UTC+2).
const PRINTER: AgentTicketSummary = {
  id: 42,
  subject: 'Printer broken',
  category: 'GENERAL',
  referenceNumber: 'ABC-1',
  status: 'ANSWERED',
  customer: { id: 100, name: 'Carol Customer', email: 'carol@example.com' },
  assignee: { id: 5, name: 'Bob Agent' },
  lastMessageAt: '2026-10-20T12:00:00Z',
};
const CLAIM: AgentTicketSummary = {
  id: 7,
  subject: 'Damaged parcel',
  category: 'CLAIM',
  referenceNumber: null,
  status: 'OPEN',
  customer: { id: 101, name: 'Dave Customer', email: 'dave@example.com' },
  assignee: null,
  lastMessageAt: '2026-10-18T12:00:00Z',
};

describe('TicketQueue', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    const storage = new MemoryStorage();
    storage.setItem(SESSION_KEY, JSON.stringify(SESSION));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'staff', component: TicketQueue },
          { path: 'staff/customers/:customerId', component: TicketQueue },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: storage },
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

  async function stable(): Promise<void> {
    await harness.fixture.whenStable();
  }

  // Matches the path only; parameter checks live in the tests that are about them.
  function expectTickets(): TestRequest {
    return httpTesting.expectOne((r) => r.method === 'GET' && r.url === AGENT_TICKETS_URL);
  }

  function expectAgents(): TestRequest {
    return httpTesting.expectOne({ method: 'GET', url: AGENTS_URL });
  }

  // Opens the page and answers the agent list; the queue request stays pending.
  async function open(url = '/staff'): Promise<void> {
    await harness.navigateByUrl(url, TicketQueue);
    expectAgents().flush(AGENTS);
    await stable();
  }

  async function flush(req: TestRequest, tickets: AgentTicketSummary[]): Promise<void> {
    req.flush(tickets);
    await stable();
  }

  async function selectStatus(label: string): Promise<void> {
    const button = [...root().querySelectorAll('mat-button-toggle button')].find(
      (b) => b.textContent!.trim() === label,
    ) as HTMLButtonElement;
    button.click();
    await stable();
  }

  // The real mat-select: open it with a click and read the options from the overlay.
  async function openAssignee(): Promise<HTMLElement[]> {
    root().querySelector<HTMLElement>('.mat-mdc-select-trigger')!.click();
    await stable();
    return [
      ...document.querySelector('.cdk-overlay-container')!.querySelectorAll<HTMLElement>('mat-option'),
    ];
  }

  async function chooseAssignee(label: string): Promise<void> {
    const option = (await openAssignee()).find((o) => o.textContent!.trim() === label)!;
    option.click();
    await stable();
  }

  function spinner(): Element | null {
    return root().querySelector('mat-progress-spinner');
  }

  function rows(): HTMLElement[] {
    return [...root().querySelectorAll<HTMLElement>('tr.mat-mdc-row')];
  }

  function cell(row: HTMLElement, column: string): string {
    return row.querySelector(`.mat-column-${column}`)!.textContent!.trim();
  }

  // Null when the subject cell has no link.
  function subjectHref(row: HTMLElement): string | null {
    return row.querySelector('.mat-column-subject a')?.getAttribute('href') ?? null;
  }

  function headers(): string[] {
    return [...root().querySelectorAll('th.mat-mdc-header-cell')].map((th) => th.textContent!.trim());
  }

  function buttonNamed(label: string): HTMLButtonElement | undefined {
    return [...root().querySelectorAll('button')].find((b) => b.textContent!.trim() === label);
  }

  describe('queue', () => {
    it('loads with Active and Anyone first', async () => {
      await open();
      const req = expectTickets();
      expect(req.request.urlWithParams).toBe(ACTIVE_URL);
      await flush(req, [PRINTER]);
      expect(rows().length).toBe(1);
    });

    it('labels the spinner Loading tickets', async () => {
      await open();
      const req = expectTickets();
      expect(spinner()!.getAttribute('aria-label')).toBe('Loading tickets');
      await flush(req, []);
      expect(spinner()).toBeNull();
    });

    it('shows the columns and the fields of each row in server order', async () => {
      await open();
      await flush(expectTickets(), [PRINTER, CLAIM]);

      expect(headers()).toEqual([
        'Subject',
        'Customer',
        'Category',
        'Ref.',
        'Status',
        'Assignee',
        'Last activity',
      ]);
      const [first, second] = rows();
      expect(rows().length).toBe(2);
      expect(cell(first, 'subject')).toBe('Printer broken');
      expect(first.querySelector('.mat-column-customer .name')!.textContent!.trim()).toBe(
        'Carol Customer',
      );
      expect(first.querySelector('.mat-column-customer .email')!.textContent!.trim()).toBe(
        'carol@example.com',
      );
      expect(cell(first, 'category')).toBe('General');
      expect(cell(first, 'referenceNumber')).toBe('ABC-1');
      expect(cell(first, 'status')).toBe('Answered');
      expect(cell(first, 'assignee')).toBe('Bob Agent');
      // The date only: the hour depends on the machine time zone.
      expect(cell(first, 'lastMessageAt')).toContain('Oct 20, 2026');
      expect(cell(second, 'subject')).toBe('Damaged parcel');
    });

    it('shows Unassigned for a null assignee and an empty cell for a null reference number', async () => {
      await open();
      await flush(expectTickets(), [CLAIM]);

      const [row] = rows();
      expect(cell(row, 'assignee')).toBe('Unassigned');
      expect(cell(row, 'referenceNumber')).toBe('');
    });

    it('links each subject, and only the subject, to the ticket thread', async () => {
      await open();
      await flush(expectTickets(), [PRINTER, CLAIM]);

      expect(rows().map(subjectHref)).toEqual(['/staff/tickets/42', '/staff/tickets/7']);
      expect(rows().map((row) => row.querySelectorAll('a').length)).toEqual([1, 1]);
      expect(rows()[0].querySelector('.mat-column-subject a')?.textContent?.trim()).toBe(
        'Printer broken',
      );
    });

    it('sends no status parameter for All', async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);

      await selectStatus('All');
      const req = expectTickets();
      expect(req.request.urlWithParams).toBe('/api/agent/tickets');
      await flush(req, [PRINTER, CLAIM]);
    });

    it('sends no request when the checked toggle is clicked', async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);

      await selectStatus('Active');
      httpTesting.expectNone((r) => r.url === AGENT_TICKETS_URL);
    });

    it('cancels the pending request when a filter changes and shows only the new response', async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);

      await selectStatus('Open');
      const pending = expectTickets();
      await selectStatus('Closed');
      const current = expectTickets();

      expect(pending.cancelled).toBe(true);
      expect(current.request.urlWithParams).toBe('/api/agent/tickets?status=CLOSED');
      await flush(current, [CLAIM]);
      expect(rows().map((row) => cell(row, 'subject'))).toEqual(['Damaged parcel']);
    });

    it('shows the error detail and retries with the current filters', async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);
      await selectStatus('Closed');
      await flush(expectTickets(), []);
      await chooseAssignee('Bob Agent');
      const failed = expectTickets();
      expect(failed.request.urlWithParams).toBe('/api/agent/tickets?status=CLOSED&assigneeId=5');
      failed.flush(
        { status: 500, title: 'Internal Server Error', detail: 'Database is down.' },
        { status: 500, statusText: 'Internal Server Error' },
      );
      await stable();
      expect(root().querySelector('[role=alert]')!.textContent!.trim()).toBe('Database is down.');

      buttonNamed('Retry')!.click();
      await stable();
      const req = expectTickets();
      expect(req.request.urlWithParams).toBe('/api/agent/tickets?status=CLOSED&assigneeId=5');
      await flush(req, [CLAIM]);
      expect(rows().length).toBe(1);
    });

    it('shows "No tickets match these filters" for an empty Active response', async () => {
      await open();
      await flush(expectTickets(), []);

      expect(root().querySelector('.empty')!.textContent!.trim()).toBe(
        'No tickets match these filters',
      );
    });

    it('shows "No tickets yet" for an empty response with All and Anyone', async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);
      await selectStatus('All');
      await flush(expectTickets(), []);

      expect(root().querySelector('.empty')!.textContent!.trim()).toBe('No tickets yet');
    });
  });

  describe('assignee select', () => {
    it('offers Anyone, Unassigned and the agents in server order', async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);

      const options = await openAssignee();
      expect(options.map((o) => o.textContent!.trim())).toEqual([
        'Anyone',
        'Unassigned',
        'Zed Agent',
        'Alice Agent (you)',
        'Bob Agent',
      ]);
    });

    it("marks only the caller's entry with (you)", async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);

      const marked = (await openAssignee())
        .map((o) => o.textContent!.trim())
        .filter((text) => text.includes('(you)'));
      expect(marked).toEqual(['Alice Agent (you)']);
    });

    it('sends unassigned=true for Unassigned', async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);

      await chooseAssignee('Unassigned');
      const req = expectTickets();
      expect(req.request.urlWithParams).toBe(`${ACTIVE_URL}&unassigned=true`);
      await flush(req, [CLAIM]);
    });

    it("sends the agent's id for an agent", async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);

      await chooseAssignee('Bob Agent');
      const req = expectTickets();
      expect(req.request.urlWithParams).toBe(`${ACTIVE_URL}&assigneeId=5`);
      await flush(req, [PRINTER]);
    });

    // Pins library behavior: mat-select emits nothing for the selected option (ADR-016 §2).
    it('sends no request when the selected option is chosen again', async () => {
      await open();
      await flush(expectTickets(), [PRINTER]);
      await chooseAssignee('Unassigned');
      await flush(expectTickets(), [CLAIM]);

      await chooseAssignee('Unassigned');
      httpTesting.expectNone((r) => r.url === AGENT_TICKETS_URL);
    });

    it('shows "Cannot load agents" and only the fixed options when the agent list fails', async () => {
      await harness.navigateByUrl('/staff', TicketQueue);
      const queue = expectTickets();
      const agents = expectAgents();
      // The queue answers first, so a failure that replaces the queue state stays visible.
      await flush(queue, [PRINTER]);
      agents.flush(
        { status: 500, title: 'Internal Server Error', detail: 'Database is down.' },
        { status: 500, statusText: 'Internal Server Error' },
      );
      await stable();

      expect(root().querySelector('.note')!.textContent!.trim()).toBe('Cannot load agents');
      expect(root().querySelector('[role=alert]')).toBeNull();
      expect(rows().map((row) => cell(row, 'subject'))).toEqual(['Printer broken']);
      const options = await openAssignee();
      expect(options.map((o) => o.textContent!.trim())).toEqual(['Anyone', 'Unassigned']);
    });
  });

  describe("a customer's tickets", () => {
    it('sends the customer id from the route as a string', async () => {
      await open(`/staff/customers/${LARGE_ID}`);
      const req = expectTickets();
      expect(req.request.urlWithParams).toContain(`customerId=${LARGE_ID}`);
      await flush(req, [PRINTER]);
    });

    it('sends no status parameter first', async () => {
      await open(`/staff/customers/${LARGE_ID}`);
      const req = expectTickets();
      expect(req.request.urlWithParams).not.toContain('status=');
      await flush(req, [PRINTER]);
    });

    it("shows the customer's name and email from the response in the heading", async () => {
      await open('/staff/customers/100');
      const req = expectTickets();
      expect(root().querySelector('h1')!.textContent!.trim()).toBe('Customer tickets');

      await flush(req, [PRINTER]);
      expect(root().querySelector('h1')!.textContent!.trim()).toBe('Carol Customer');
      expect(root().querySelector('header .email')!.textContent!.trim()).toBe('carol@example.com');
    });

    it('keeps the heading after a filtered empty response', async () => {
      await open('/staff/customers/100');
      await flush(expectTickets(), [PRINTER]);
      await selectStatus('Closed');
      await flush(expectTickets(), []);

      expect(root().querySelector('h1')!.textContent!.trim()).toBe('Carol Customer');
      expect(root().querySelector('header .email')!.textContent!.trim()).toBe('carol@example.com');
    });

    it('hides the Customer column', async () => {
      await open('/staff/customers/100');
      await flush(expectTickets(), [PRINTER]);

      expect(rows().length).toBe(1);
      expect(headers()).not.toContain('Customer');
      expect(root().querySelector('.mat-column-customer')).toBeNull();
    });

    it('links the subject to the ticket thread', async () => {
      await open('/staff/customers/100');
      await flush(expectTickets(), [PRINTER]);

      // The ticket id, not the customer id from the route or the row.
      expect(rows().map(subjectHref)).toEqual(['/staff/tickets/42']);
    });

    it('shows "No tickets for this customer" for an empty response with the default filters', async () => {
      await open('/staff/customers/100');
      await flush(expectTickets(), []);

      expect(root().querySelector('.empty')!.textContent!.trim()).toBe(
        'No tickets for this customer',
      );
      expectAllCustomersLinkAndNoRetry();
    });

    it('shows "No tickets for this customer" for a 400', async () => {
      await open('/staff/customers/abc');
      expectTickets().flush(
        { status: 400, title: 'Bad Request', detail: 'Invalid request parameter.' },
        { status: 400, statusText: 'Bad Request' },
      );
      await stable();

      expect(root().querySelector('[role=alert]')).toBeNull();
      expect(buttonNamed('Retry')).toBeUndefined();
      expect(root().querySelector('.empty')?.textContent?.trim()).toBe(
        'No tickets for this customer',
      );
      const link = root().querySelector('header a');
      expect(link?.textContent?.trim()).toBe('All customers');
      expect(link?.getAttribute('href')).toBe('/staff/customers');
    });

    it('shows "No tickets match these filters" for an empty filtered response', async () => {
      await open('/staff/customers/100');
      await flush(expectTickets(), [PRINTER]);
      await selectStatus('Closed');
      await flush(expectTickets(), []);

      expect(root().querySelector('.empty')!.textContent!.trim()).toBe(
        'No tickets match these filters',
      );
    });

    function expectAllCustomersLinkAndNoRetry(): void {
      const link = root().querySelector('header a')!;
      expect(link.textContent!.trim()).toBe('All customers');
      expect(link.getAttribute('href')).toBe('/staff/customers');
      expect(buttonNamed('Retry')).toBeUndefined();
    }
  });
});
