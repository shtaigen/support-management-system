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
import { TicketStatus } from '../../shared/ticket/ticket';
import { AGENT_TICKETS_URL } from './agent-api';
import { AgentTicketResponse } from './agent-ticket';
import { AgentTicketThread } from './agent-ticket-thread';
import { POLL_INTERVAL_MS } from './agent-thread-store';

// The caller is Alice (id 2).
const SESSION: Session = {
  accessToken: 'token',
  expiresAt: '2999-01-01T00:00:00Z',
  user: { id: 2, name: 'Alice Agent', role: 'AGENT' },
};

// Noon UTC: the same calendar date in every zone the tests run in (UTC, UTC+2).
// Assigned to Bob (id 5), not to the caller.
const PRINTER: AgentTicketResponse = {
  id: 42,
  subject: 'Printer broken',
  category: 'POLICY',
  referenceNumber: 'ABC-1',
  status: 'CUSTOMER_REPLIED',
  customer: { id: 100, name: 'Carol Customer', email: 'carol@example.com' },
  assignee: { id: 5, name: 'Bob Agent' },
  createdAt: '2026-10-19T12:00:00Z',
  updatedAt: '2026-10-20T12:00:00Z',
  lastMessageAt: '2026-10-20T12:00:00Z',
  version: 3,
  messages: [
    {
      id: 1,
      body: 'It is broken',
      createdAt: '2026-10-19T12:00:00Z',
      author: { name: 'Carol Customer', role: 'CUSTOMER' },
    },
    {
      id: 2,
      body: 'We are on it',
      createdAt: '2026-10-20T12:00:00Z',
      author: { name: 'Bob Agent', role: 'AGENT' },
    },
  ],
};

const UNASSIGNED: AgentTicketResponse = {
  ...PRINTER,
  status: 'OPEN',
  assignee: null,
  messages: [PRINTER.messages[0]],
};

// UNASSIGNED after Alice replied "Hello": ANSWERED and assigned to her (TR-02, BR-09).
const REPLIED: AgentTicketResponse = {
  ...UNASSIGNED,
  status: 'ANSWERED',
  assignee: { id: 2, name: 'Alice Agent' },
  lastMessageAt: '2026-10-21T12:00:00Z',
  version: 4,
  messages: [
    ...UNASSIGNED.messages,
    {
      id: 3,
      body: 'Hello',
      createdAt: '2026-10-21T12:00:00Z',
      author: { name: 'Alice Agent', role: 'AGENT' },
    },
  ],
};

const LARGE_ID = '9007199254740993';
const THREAD_URL = `${AGENT_TICKETS_URL}/42`;

describe('AgentTicketThread', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    // Every timer is fake before the page can schedule a poll: no real 4 s timer runs.
    vi.useFakeTimers();
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
    const storage = new MemoryStorage();
    storage.setItem(SESSION_KEY, JSON.stringify(SESSION));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'staff/tickets/:id', component: AgentTicketThread }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: storage },
      ],
    });
    harness = await RouterTestingHarness.create();
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    delete (document as { visibilityState?: unknown }).visibilityState;
    vi.useRealTimers();
    httpTesting.verify();
  });

  function root(): HTMLElement {
    return harness.routeNativeElement!;
  }

  function render(): void {
    harness.detectChanges();
  }

  async function open(id = '42'): Promise<AgentTicketThread> {
    return harness.navigateByUrl(`/staff/tickets/${id}`, AgentTicketThread);
  }

  async function openLoaded(ticket: AgentTicketResponse = PRINTER): Promise<AgentTicketThread> {
    const thread = await open();
    expectGet().flush(ticket);
    render();
    return thread;
  }

  function expectGet(): TestRequest {
    return httpTesting.expectOne({ method: 'GET', url: THREAD_URL });
  }

  function expectPost(): TestRequest {
    return httpTesting.expectOne({ method: 'POST', url: `${THREAD_URL}/messages` });
  }

  function expectNoPost(): void {
    httpTesting.expectNone({ method: 'POST', url: `${THREAD_URL}/messages` });
  }

  // The next poll, answered with the given ticket.
  function poll(ticket: AgentTicketResponse): void {
    vi.advanceTimersByTime(POLL_INTERVAL_MS);
    expectGet().flush(ticket);
    render();
  }

  function fail(req: TestRequest, status: number, detail = `Failure ${status}.`): void {
    req.flush({ status, title: 'Failure', detail }, { status, statusText: 'Failure' });
    render();
  }

  // Helpers return null for a missing element, so a test fails on its assertion, not a TypeError.
  function text(selector: string): string | null {
    return root().querySelector(selector)?.textContent?.trim() ?? null;
  }

  function spinner(): Element | null {
    return root().querySelector('mat-progress-spinner');
  }

  function textarea(): HTMLTextAreaElement | null {
    return root().querySelector('textarea');
  }

  function sendButton(): HTMLButtonElement | null {
    return root().querySelector<HTMLButtonElement>('button[type=submit]');
  }

  function type(value: string): void {
    const input = textarea()!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    render();
  }

  function blur(): void {
    textarea()!.dispatchEvent(new Event('blur'));
    render();
  }

  // Submits through the form, as a user does: unlike send(), this marks the form submitted.
  function clickSend(): void {
    sendButton()!.click();
    render();
  }

  // Material's error state of the field: the class colors the label red, aria-invalid is what
  // assistive technology reads. Both follow MatInput.errorState.
  function expectNoErrorState(): void {
    expect(root().querySelector('mat-form-field')?.classList).not.toContain(
      'mat-form-field-invalid',
    );
    expect(textarea()?.getAttribute('aria-invalid')).toBe('false');
  }

  // Types and sends a message, leaving the POST pending.
  function post(thread: AgentTicketThread, value = 'Hello'): TestRequest {
    type(value);
    thread.send();
    return expectPost();
  }

  describe('states', () => {
    it('shows the Loading conversation spinner until the first response', async () => {
      await open();
      render();
      expect(spinner()?.getAttribute('aria-label')).toBe('Loading conversation');
      expect(textarea()).toBeNull();

      expectGet().flush(PRINTER);
      render();
      expect(spinner()).toBeNull();
      expect(text('h1')).toBe('Printer broken');
    });

    it('a first-load 5xx shows the detail and Retry', async () => {
      await open();
      fail(expectGet(), 500, 'Database is down.');
      expect(text('[role=alert]')).toBe('Database is down.');
      expect(textarea()).toBeNull();

      const retry = [...root().querySelectorAll('button')].find(
        (b) => b.textContent?.trim() === 'Retry',
      );
      expect(retry).toBeDefined();
      retry?.click();
      render();
      expect(spinner()).not.toBeNull();
      expectGet().flush(PRINTER);
      render();
      expect(text('h1')).toBe('Printer broken');
    });

    it('not found links back to the queue and shows no composer', async () => {
      await open();
      fail(expectGet(), 404);

      expect(text('h1')).toBe('Ticket not found');
      const link = root().querySelector('a');
      expect(link?.textContent?.trim()).toBe('Back to the queue');
      expect(link?.getAttribute('href')).toBe('/staff');
      expect(textarea()).toBeNull();
    });

    it('a poll in flight shows no spinner', async () => {
      await openLoaded();
      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      const req = expectGet();
      render();

      expect(spinner()).toBeNull();
      expect(text('h1')).toBe('Printer broken');
      req.flush(PRINTER);
    });

    it('shows Cannot refresh the conversation after a failed refresh', async () => {
      await openLoaded();
      expect(text('.note')).toBeNull();

      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      fail(expectGet(), 503);
      expect(text('[role=status]')).toBe('Cannot refresh the conversation');
      expect(text('h1')).toBe('Printer broken');
    });

    it(`requests the exact id ${LARGE_ID} from the route`, async () => {
      await open(LARGE_ID);
      // A conversion to a number would request ...992.
      httpTesting
        .expectOne({ method: 'GET', url: `${AGENT_TICKETS_URL}/${LARGE_ID}` })
        .flush(PRINTER);
    });
  });

  describe('header', () => {
    it('shows subject, status, category, Ref. and date', async () => {
      await openLoaded();

      expect(text('h1')).toBe('Printer broken');
      expect(text('.status')).toBe('Customer replied');
      expect(text('.category')).toBe('Policy');
      expect(text('.reference')).toBe('Ref. ABC-1');
      // The date only: the hour depends on the machine time zone.
      expect(text('.created')).toContain('Oct 19, 2026');
    });

    it("shows the customer's name as a link to their tickets and the email", async () => {
      await openLoaded();

      const link = root().querySelector('header .customer a');
      expect(link?.textContent?.trim()).toBe('Carol Customer');
      expect(link?.getAttribute('href')).toBe('/staff/customers/100');
      expect(text('header .customer .email')).toBe('carol@example.com');
    });

    it('shows Unassigned for a null assignee', async () => {
      await openLoaded(UNASSIGNED);
      expect(text('.assignee')).toBe('Unassigned');
    });

    it('marks only the caller as the assignee with (you)', async () => {
      await openLoaded({ ...PRINTER, assignee: { id: 2, name: 'Alice Agent' } });
      expect(text('.assignee')).toBe('Assigned to Alice Agent (you)');

      poll(PRINTER);
      expect(text('.assignee')).toBe('Assigned to Bob Agent');
    });

    it('shows no Ref. for a null reference number', async () => {
      await openLoaded({ ...PRINTER, referenceNumber: null });

      expect(root().querySelector('.reference')).toBeNull();
      expect(text('header')).not.toContain('Ref.');
      expect(text('header')).not.toContain('null');
    });

    it('shows the new status and assignee after a reply to an unassigned ticket', async () => {
      const thread = await openLoaded(UNASSIGNED);
      expect(text('.status')).toBe('Open');
      expect(text('.assignee')).toBe('Unassigned');

      post(thread).flush(REPLIED);
      render();
      expect(text('.status')).toBe('Answered');
      expect(text('.assignee')).toBe('Assigned to Alice Agent (you)');
      expect(root().querySelectorAll('.message').length).toBe(2);
    });
  });

  describe('messages', () => {
    it('labels messages with the author name and role in server order', async () => {
      // Not chronological, to show that the client does not sort.
      const [customer, agent] = PRINTER.messages;
      await openLoaded({ ...PRINTER, messages: [agent, customer] });

      const items = [...root().querySelectorAll('.message')];
      expect(items.map((item) => item.querySelector('.author')?.textContent?.trim())).toEqual([
        'Bob Agent · Agent',
        'Carol Customer · Customer',
      ]);
      expect(items.map((item) => item.querySelector('.body')?.textContent)).toEqual([
        'We are on it',
        'It is broken',
      ]);
      expect(items[0].classList).toContain('agent');
      expect(items[1].classList).toContain('customer');
      expect(items[0].textContent).toContain('Oct 20, 2026');
    });

    it('marks no message as You, also for the caller', async () => {
      await openLoaded(REPLIED);

      expect(text('.message:last-child .author')).toBe('Alice Agent · Agent');
      // Case-sensitive: the header's "(you)" is the assignee, not a message mark.
      expect(root().textContent).not.toContain('You');
    });

    it('renders <b>x</b> as text', async () => {
      await openLoaded({
        ...PRINTER,
        messages: [{ ...PRINTER.messages[0], body: '<b>x</b>' }],
      });

      const body = root().querySelector('.message .body');
      expect(body?.textContent).toBe('<b>x</b>');
      expect(body?.querySelector('b')).toBeNull();
    });
  });

  describe('composer', () => {
    it('sends the typed body', async () => {
      const thread = await openLoaded();
      const req = post(thread, '  Hello  ');

      expect(req.request.body).toEqual({ body: '  Hello  ' });
      req.flush(REPLIED);
    });

    it('a double submit sends one request', async () => {
      const thread = await openLoaded();
      type('Hello');
      // Direct calls, not clicks: the disabled button would hide a missing guard.
      thread.send();
      thread.send();
      expectPost().flush(REPLIED);
    });

    it('a successful post clears the text', async () => {
      const thread = await openLoaded();
      post(thread).flush(REPLIED);
      render();

      expect(textarea()?.value).toBe('');
      expect(text('.count')).toBe('0 / 4000');
    });

    it('a stale successful post still clears the text', async () => {
      const thread = await openLoaded();
      const req = post(thread);
      vi.advanceTimersByTime(POLL_INTERVAL_MS); // this poll starts after the post
      expectGet().flush(PRINTER);

      req.flush(REPLIED);
      render();
      expect(text('.status')).toBe('Customer replied'); // the stale thread was not applied
      expect(textarea()?.value).toBe('');
    });

    it('a post error keeps the text and shows the detail', async () => {
      const thread = await openLoaded();
      fail(post(thread), 500);

      expect(textarea()?.value).toBe('Hello');
      expect(text('.post-error')).toBe('Failure 500.');
    });

    it('a server 400 shows its detail under the composer', async () => {
      const thread = await openLoaded();
      // NUL is not checked on the client (ADR-015 §5); the server rejects it.
      fail(post(thread, 'a\u0000b'), 400, 'Validation failed.');

      expect(text('.post-error')).toBe('Validation failed.');
      expect(text('h1')).toBe('Printer broken');
    });

    it('editing the text clears the post error', async () => {
      const thread = await openLoaded();
      fail(post(thread), 500);
      expect(text('.post-error')).toBe('Failure 500.');

      type('Hello!');
      expect(text('.post-error')).toBeNull();
    });

    it('a 409 shows the detail, keeps the text and reloads the ticket', async () => {
      const thread = await openLoaded();
      fail(post(thread), 409, 'The ticket is closed.');

      expect(text('.post-error')).toBe('The ticket is closed.');
      expect(textarea()?.value).toBe('Hello');
      expectGet().flush({ ...PRINTER, status: 'CLOSED' });
      render();
      expect(text('.status')).toBe('Closed');
      expect(textarea()?.value).toBe('Hello');
    });

    it('a post 401 keeps the text and shows no detail', async () => {
      const thread = await openLoaded();
      fail(post(thread), 401);

      expect(textarea()?.value).toBe('Hello');
      expect(root().querySelector('[role=alert]')).toBeNull();
    });

    it('shows 0 / 4000 before input and the typed length after', async () => {
      await openLoaded();
      expect(text('.count')).toBe('0 / 4000');

      type('Hello');
      expect(text('.count')).toBe('5 / 4000');
    });

    it('4000 characters can be sent, 4001 cannot', async () => {
      const thread = await openLoaded();
      type('a'.repeat(4001));
      expect(sendButton()?.disabled).toBe(true);
      thread.send();
      expectNoPost();

      type('a'.repeat(4000));
      expect(sendButton()?.disabled).toBe(false);
      thread.send();
      expectPost().flush(REPLIED);
    });

    it('4000 characters plus a trailing space cannot be sent', async () => {
      const thread = await openLoaded();
      type('a'.repeat(4000) + ' ');

      expect(sendButton()?.disabled).toBe(true);
      thread.send();
      expectNoPost();
    });

    it('a body of only U+00A0 is valid and is sent', async () => {
      const thread = await openLoaded();
      type(' ');

      expect(sendButton()?.disabled).toBe(false);
      thread.send();
      const req = expectPost();
      expect(req.request.body).toEqual({ body: ' ' });
      req.flush(REPLIED);
    });

    for (const [name, value] of [
      ['U+001C', '\u001C'],
      ['spaces, tabs and newlines', ' \t\n \r\n'],
    ]) {
      it(`a body of only ${name} is invalid`, async () => {
        const thread = await openLoaded();
        type(value);

        expect(sendButton()?.disabled).toBe(true);
        thread.send();
        expectNoPost();
      });
    }

    describe('error state (ADR-015 §6)', () => {
      it('an empty field is not in the error state after blur', async () => {
        await openLoaded();
        blur();

        expectNoErrorState();
        expect(sendButton()?.disabled).toBe(true);
      });

      it('the field is not in the error state after Send clears the text', async () => {
        await openLoaded();
        type('test');
        clickSend();
        expectPost().flush(REPLIED);
        render();

        expect(root().querySelector('form')?.classList).toContain('ng-submitted');
        expect(textarea()?.value).toBe('');
        expectNoErrorState();
        expect(sendButton()?.disabled).toBe(true);
      });

      it('over 4000 characters is not in the error state and Send is disabled', async () => {
        await openLoaded();
        type('a'.repeat(4001));
        blur();

        expectNoErrorState();
        expect(sendButton()?.disabled).toBe(true);
        expect(text('.count')).toBe('4001 / 4000');
      });

      for (const status of [400, 500]) {
        it(`a server ${status} detail is still shown under the composer`, async () => {
          await openLoaded();
          type('Hello');
          clickSend();
          fail(expectPost(), status, `Server said ${status}.`);

          expect(text('.post-error')).toBe(`Server said ${status}.`);
          expect(root().querySelector('.post-error')?.getAttribute('role')).toBe('alert');
        });
      }
    });
  });

  describe('CLOSED', () => {
    it('disables Send with valid text and shows the closed hint', async () => {
      await openLoaded({ ...PRINTER, status: 'CLOSED' });
      type('Hello');

      // The disabled button is the only guard (ADR-016 §3); a racing post gets a 409.
      expect(sendButton()?.disabled).toBe(true);
      expect(textarea()?.disabled).toBe(false);
      expect(text('.closed')).toBe('This ticket is closed. Reopen it to reply.');
    });

    for (const status of ['OPEN', 'ANSWERED', 'CUSTOMER_REPLIED'] satisfies TicketStatus[]) {
      it(`enables Send with valid text and shows no closed hint for ${status}`, async () => {
        await openLoaded({ ...PRINTER, status });
        type('Hello');

        expect(sendButton()?.disabled).toBe(false);
        expect(root().querySelector('.closed')).toBeNull();
      });
    }

    it('a poll that brings CLOSED keeps the typed text and disables Send', async () => {
      await openLoaded();
      type('Draft reply');
      expect(sendButton()?.disabled).toBe(false);

      poll({ ...PRINTER, status: 'CLOSED' });
      expect(text('.status')).toBe('Closed');
      expect(textarea()?.value).toBe('Draft reply');
      expect(sendButton()?.disabled).toBe(true);
    });

    it('a poll that brings OPEN again enables Send', async () => {
      await openLoaded({ ...PRINTER, status: 'CLOSED' });
      type('Draft reply');
      expect(sendButton()?.disabled).toBe(true);

      poll({ ...PRINTER, status: 'OPEN' });
      expect(textarea()?.value).toBe('Draft reply');
      expect(sendButton()?.disabled).toBe(false);
      expect(root().querySelector('.closed')).toBeNull();
    });
  });
});
