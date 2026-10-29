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
import { CustomerTicketResponse, TicketStatus } from './customer-ticket';
import { TICKETS_URL } from './customer-tickets-api';
import { TicketThread } from './ticket-thread';
import { POLL_INTERVAL_MS } from './ticket-thread-store';

// Noon UTC: the same calendar date in every zone the tests run in (UTC, UTC+2).
const PRINTER: CustomerTicketResponse = {
  id: 42,
  subject: 'Printer broken',
  category: 'POLICY',
  referenceNumber: 'ABC-1',
  status: 'ANSWERED',
  createdAt: '2026-10-19T12:00:00Z',
  lastMessageAt: '2026-10-20T12:00:00Z',
  messages: [
    { id: 1, body: 'It is broken', createdAt: '2026-10-19T12:00:00Z', authorRole: 'CUSTOMER' },
    { id: 2, body: 'We are on it', createdAt: '2026-10-20T12:00:00Z', authorRole: 'AGENT' },
  ],
};

// PRINTER after the customer posted "Hello" (TR-03).
const REPLIED: CustomerTicketResponse = {
  ...PRINTER,
  status: 'CUSTOMER_REPLIED',
  lastMessageAt: '2026-10-21T12:00:00Z',
  messages: [
    ...PRINTER.messages,
    { id: 3, body: 'Hello', createdAt: '2026-10-21T12:00:00Z', authorRole: 'CUSTOMER' },
  ],
};

const THREAD_URL = `${TICKETS_URL}/42`;

describe('TicketThread', () => {
  let harness: RouterTestingHarness;
  let httpTesting: HttpTestingController;

  beforeEach(async () => {
    // Every timer is fake before the page can schedule a poll: no real 4 s timer runs.
    vi.useFakeTimers();
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: 'client/tickets/:id', component: TicketThread }]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: new MemoryStorage() },
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

  async function open(id = '42'): Promise<TicketThread> {
    return harness.navigateByUrl(`/client/tickets/${id}`, TicketThread);
  }

  async function openLoaded(ticket: CustomerTicketResponse = PRINTER): Promise<TicketThread> {
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

  function fail(req: TestRequest, status: number, detail = `Failure ${status}.`): void {
    req.flush({ status, title: 'Failure', detail }, { status, statusText: 'Failure' });
    render();
  }

  function text(selector: string): string | null {
    return root().querySelector(selector)?.textContent!.trim() ?? null;
  }

  function spinner(): Element | null {
    return root().querySelector('mat-progress-spinner');
  }

  function textarea(): HTMLTextAreaElement | null {
    return root().querySelector('textarea');
  }

  function sendButton(): HTMLButtonElement {
    return root().querySelector<HTMLButtonElement>('button[type=submit]')!;
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
    sendButton().click();
    render();
  }

  // Material's error state of the field: the class colors the label red, aria-invalid is what
  // assistive technology reads. Both follow MatInput.errorState.
  function expectNoErrorState(): void {
    expect(root().querySelector('mat-form-field')!.classList).not.toContain(
      'mat-form-field-invalid',
    );
    expect(textarea()!.getAttribute('aria-invalid')).toBe('false');
  }

  // Types and sends a message, leaving the POST pending.
  function post(thread: TicketThread, value = 'Hello'): TestRequest {
    type(value);
    thread.send();
    return expectPost();
  }

  describe('states', () => {
    it('shows the Loading conversation spinner until the first response', async () => {
      await open();
      render();
      expect(spinner()!.getAttribute('aria-label')).toBe('Loading conversation');
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
        (b) => b.textContent!.trim() === 'Retry',
      )!;
      retry.click();
      render();
      expect(spinner()).not.toBeNull();
      expectGet().flush(PRINTER);
      render();
      expect(text('h1')).toBe('Printer broken');
    });

    it('not found shows the link to the list and no composer', async () => {
      await open();
      fail(expectGet(), 404);

      expect(text('h1')).toBe('Ticket not found');
      expect(root().querySelector('a')!.getAttribute('href')).toBe('/client');
      expect(textarea()).toBeNull();
    });

    it('a poll in flight shows no spinner', async () => {
      await openLoaded();
      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      const poll = expectGet();
      render();

      expect(spinner()).toBeNull();
      expect(text('h1')).toBe('Printer broken');
      poll.flush(PRINTER);
    });

    it('shows Cannot refresh the conversation after a failed refresh', async () => {
      await openLoaded();
      expect(text('.note')).toBeNull();

      vi.advanceTimersByTime(POLL_INTERVAL_MS);
      fail(expectGet(), 503);
      expect(text('[role=status]')).toBe('Cannot refresh the conversation');
      expect(text('h1')).toBe('Printer broken');
    });

    it('requests the exact id 9007199254740993', async () => {
      await open('9007199254740993');
      httpTesting
        .expectOne({ method: 'GET', url: `${TICKETS_URL}/9007199254740993` })
        .flush(PRINTER);
    });
  });

  describe('header and messages', () => {
    it('shows subject, status, category, Ref. and date', async () => {
      await openLoaded();

      expect(text('h1')).toBe('Printer broken');
      expect(text('.status')).toBe('Answered');
      expect(text('.category')).toBe('Policy');
      expect(text('.reference')).toBe('Ref. ABC-1');
      // The date only: the hour depends on the machine time zone.
      expect(text('.created')).toContain('Oct 19, 2026');
    });

    it('shows no Ref. for a null reference number', async () => {
      await openLoaded({ ...PRINTER, referenceNumber: null });

      expect(root().querySelector('.reference')).toBeNull();
      expect(text('header')).not.toContain('Ref.');
      expect(text('header')).not.toContain('null');
    });

    it('labels messages You and Agent in server order', async () => {
      // Not chronological, to show that the client does not sort.
      const [customer, agent] = PRINTER.messages;
      await openLoaded({ ...PRINTER, messages: [agent, customer] });

      const items = [...root().querySelectorAll('.message')];
      expect(items.map((item) => item.querySelector('.author')!.textContent!.trim())).toEqual([
        'Agent',
        'You',
      ]);
      expect(items.map((item) => item.querySelector('.body')!.textContent)).toEqual([
        'We are on it',
        'It is broken',
      ]);
      expect(items[0].classList).toContain('agent');
      expect(items[1].classList).toContain('customer');
      expect(items[0].textContent).toContain('Oct 20, 2026');
    });

    it('renders <b>x</b> as text', async () => {
      await openLoaded({
        ...PRINTER,
        messages: [{ ...PRINTER.messages[0], body: '<b>x</b>' }],
      });

      const body = root().querySelector('.message .body')!;
      expect(body.textContent).toBe('<b>x</b>');
      expect(body.querySelector('b')).toBeNull();
    });

    it('the header shows the new status after a successful post', async () => {
      const thread = await openLoaded();
      expect(text('.status')).toBe('Answered');

      post(thread).flush(REPLIED);
      render();
      expect(text('.status')).toBe('Customer replied');
      expect(root().querySelectorAll('.message').length).toBe(3);
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

      expect(textarea()!.value).toBe('');
      expect(text('.count')).toBe('0 / 4000');
    });

    it('a stale successful post still clears the text', async () => {
      const thread = await openLoaded();
      const req = post(thread);
      vi.advanceTimersByTime(POLL_INTERVAL_MS); // this poll starts after the post
      expectGet().flush(PRINTER);

      req.flush(REPLIED);
      render();
      expect(text('.status')).toBe('Answered'); // the stale thread was not applied
      expect(textarea()!.value).toBe('');
    });

    it('a post error keeps the text and shows the detail', async () => {
      const thread = await openLoaded();
      fail(post(thread), 500);

      expect(textarea()!.value).toBe('Hello');
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

    it('a 409 shows the detail and reloads the thread', async () => {
      const thread = await openLoaded();
      fail(post(thread), 409, 'The ticket was changed at the same time.');

      expect(text('.post-error')).toBe('The ticket was changed at the same time.');
      expect(textarea()!.value).toBe('Hello');
      expectGet().flush(REPLIED);
      render();
      expect(text('.status')).toBe('Customer replied');
    });

    it('a post 401 keeps the text and shows no detail', async () => {
      const thread = await openLoaded();
      fail(post(thread), 401);

      expect(textarea()!.value).toBe('Hello');
      expect(root().querySelector('[role=alert]')).toBeNull();
    });

    it('shows the reopen hint for CLOSED and hides it after the post reopens the ticket', async () => {
      const thread = await openLoaded({ ...PRINTER, status: 'CLOSED' });
      expect(text('.reopen')).toBe('Sending a message reopens this ticket.');

      post(thread).flush(REPLIED);
      render();
      expect(root().querySelector('.reopen')).toBeNull();
    });

    for (const status of ['OPEN', 'ANSWERED', 'CUSTOMER_REPLIED'] satisfies TicketStatus[]) {
      it(`shows no reopen hint for ${status}`, async () => {
        await openLoaded({ ...PRINTER, status });
        expect(root().querySelector('.reopen')).toBeNull();
      });
    }

    it('shows 0 / 4000 before input and the typed length after', async () => {
      await openLoaded();
      expect(text('.count')).toBe('0 / 4000');

      type('Hello');
      expect(text('.count')).toBe('5 / 4000');
    });

    it('4000 characters can be sent, 4001 cannot', async () => {
      const thread = await openLoaded();
      type('a'.repeat(4001));
      expect(sendButton().disabled).toBe(true);
      thread.send();
      httpTesting.expectNone({ method: 'POST', url: `${THREAD_URL}/messages` });

      type('a'.repeat(4000));
      expect(sendButton().disabled).toBe(false);
      thread.send();
      expectPost().flush(REPLIED);
    });

    it('4000 characters plus a trailing space cannot be sent', async () => {
      const thread = await openLoaded();
      type('a'.repeat(4000) + ' ');

      expect(sendButton().disabled).toBe(true);
      thread.send();
      httpTesting.expectNone({ method: 'POST', url: `${THREAD_URL}/messages` });
    });

    it('a body of only U+00A0 is valid and is sent', async () => {
      const thread = await openLoaded();
      type('\u00A0');

      expect(sendButton().disabled).toBe(false);
      thread.send();
      const req = expectPost();
      expect(req.request.body).toEqual({ body: '\u00A0' });
      req.flush(REPLIED);
    });

    for (const [name, value] of [
      ['U+001C', '\u001C'],
      ['spaces, tabs and newlines', ' \t\n \r\n'],
    ]) {
      it(`a body of only ${name} is invalid`, async () => {
        const thread = await openLoaded();
        type(value);

        expect(sendButton().disabled).toBe(true);
        thread.send();
        httpTesting.expectNone({ method: 'POST', url: `${THREAD_URL}/messages` });
      });
    }

    describe('error state (ADR-015 §6)', () => {
      it('an empty field is not in the error state after blur', async () => {
        await openLoaded();
        blur();

        expectNoErrorState();
        expect(sendButton().disabled).toBe(true);
      });

      it('the field is not in the error state after Send clears the text', async () => {
        await openLoaded();
        type('test');
        clickSend();
        expectPost().flush(REPLIED);
        render();

        expect(root().querySelector('form')!.classList).toContain('ng-submitted');
        expect(textarea()!.value).toBe('');
        expectNoErrorState();
        expect(sendButton().disabled).toBe(true);
      });

      it('over 4000 characters is not in the error state and Send is disabled', async () => {
        await openLoaded();
        type('a'.repeat(4001));
        blur();

        expectNoErrorState();
        expect(sendButton().disabled).toBe(true);
        expect(text('.count')).toBe('4001 / 4000');
      });

      for (const status of [400, 500]) {
        it(`a server ${status} detail is still shown under the composer`, async () => {
          await openLoaded();
          type('Hello');
          clickSend();
          fail(expectPost(), status, `Server said ${status}.`);

          expect(text('.post-error')).toBe(`Server said ${status}.`);
          expect(root().querySelector('.post-error')!.getAttribute('role')).toBe('alert');
        });
      }
    });
  });
});
