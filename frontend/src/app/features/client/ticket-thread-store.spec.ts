import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  TestRequest,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { Component, inject } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MemoryStorage } from '../../core/auth/memory-storage';
import { SESSION_STORAGE } from '../../core/auth/session';
import { CustomerTicketResponse, TicketStatus } from './customer-ticket';
import { TICKETS_URL } from './customer-tickets-api';
import { POLL_INTERVAL_MS, ThreadState, TicketThreadStore } from './ticket-thread-store';

// The store is provided by a host component, so fixture.destroy() destroys the DestroyRef the
// store injected, as it does for the real page.
@Component({ template: '', providers: [TicketThreadStore] })
class Host {
  readonly store = inject(TicketThreadStore);
}

const THREAD_URL = `${TICKETS_URL}/42`;

function ticket(status: TicketStatus): CustomerTicketResponse {
  return {
    id: 42,
    subject: 'Printer broken',
    category: 'GENERAL',
    referenceNumber: null,
    status,
    createdAt: '2026-10-19T12:00:00Z',
    lastMessageAt: '2026-10-19T12:00:00Z',
    messages: [
      { id: 1, body: 'It is broken', createdAt: '2026-10-19T12:00:00Z', authorRole: 'CUSTOMER' },
    ],
  };
}

describe('TicketThreadStore', () => {
  let fixture: ComponentFixture<Host>;
  let store: TicketThreadStore;
  let httpTesting: HttpTestingController;
  let visibility: DocumentVisibilityState;

  beforeEach(() => {
    vi.useFakeTimers();
    visibility = 'visible';
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => visibility,
    });
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SESSION_STORAGE, useValue: new MemoryStorage() },
      ],
    });
    httpTesting = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    // Restore first: a failing verify() must not leak the override or fake timers.
    delete (document as { visibilityState?: unknown }).visibilityState;
    vi.useRealTimers();
    httpTesting.verify();
  });

  function create(): void {
    fixture = TestBed.createComponent(Host);
    store = fixture.componentInstance.store;
    store.start('42');
  }

  // A first load that succeeded: polling is active and the first poll is due in 4 s.
  function createLoaded(status: TicketStatus = 'OPEN'): void {
    create();
    expectGet().flush(ticket(status));
  }

  function setVisibility(state: DocumentVisibilityState): void {
    visibility = state;
    document.dispatchEvent(new Event('visibilitychange'));
  }

  function advance(ms: number): void {
    vi.advanceTimersByTime(ms);
  }

  function expectGet(): TestRequest {
    return httpTesting.expectOne({ method: 'GET', url: THREAD_URL });
  }

  function expectPost(): TestRequest {
    return httpTesting.expectOne({ method: 'POST', url: `${THREAD_URL}/messages` });
  }

  function expectNoRequest(): void {
    httpTesting.expectNone(() => true);
  }

  function fail(req: TestRequest, status: number): void {
    req.flush(
      { status, title: 'Failure', detail: `Failure ${status}.` },
      { status, statusText: 'Failure' },
    );
  }

  // Records how the post's own observable ended: 'done' or the error status.
  function send(): { outcome?: 'done' | number } {
    const result: { outcome?: 'done' | number } = {};
    store.send('Hello').subscribe({
      complete: () => (result.outcome = 'done'),
      error: (error: { status: number }) => (result.outcome = error.status),
    });
    return result;
  }

  function loaded(): Extract<ThreadState, { kind: 'loaded' }> {
    const state = store.state();
    if (state.kind !== 'loaded') {
      throw new Error(`Expected a loaded thread, got ${state.kind}`);
    }
    return state;
  }

  describe('scheduling', () => {
    it('the first success schedules exactly one poll', () => {
      createLoaded();
      advance(POLL_INTERVAL_MS - 1);
      expectNoRequest();

      advance(1);
      expectGet().flush(ticket('OPEN'));
      advance(POLL_INTERVAL_MS);
      expectGet().flush(ticket('OPEN'));
    });

    it('a slow poll produces no second request', () => {
      createLoaded();
      advance(POLL_INTERVAL_MS);
      const slow = expectGet();

      advance(10_000);
      expectNoRequest();

      slow.flush(ticket('OPEN'));
      advance(POLL_INTERVAL_MS);
      expectGet().flush(ticket('OPEN'));
    });

    it('a 409 reload does not move or add a poll', () => {
      createLoaded(); // t = 0: the first poll is due at t = 4 s
      advance(1000);
      const post = send();
      fail(expectPost(), 409);
      expect(post.outcome).toBe(409);
      const reload = expectGet();
      advance(1000);
      reload.flush(ticket('ANSWERED')); // t = 2 s

      advance(1999);
      expectNoRequest();
      advance(1); // t = 4 s: still the original schedule
      expectGet().flush(ticket('ANSWERED'));
      advance(POLL_INTERVAL_MS);
      expectGet().flush(ticket('ANSWERED'));
      expectNoRequest();
    });
  });

  describe('sequence numbers', () => {
    it('a poll that started before a post and arrives after it is ignored', () => {
      createLoaded('ANSWERED');
      advance(POLL_INTERVAL_MS);
      const poll = expectGet();
      send();
      expectPost().flush(ticket('CUSTOMER_REPLIED'));

      poll.flush(ticket('ANSWERED'));
      expect(loaded().ticket.status).toBe('CUSTOMER_REPLIED');
    });

    it('a stale poll error after a newer success shows no note', () => {
      createLoaded();
      advance(POLL_INTERVAL_MS);
      const poll = expectGet();
      send();
      expectPost().flush(ticket('OPEN'));

      fail(poll, 500);
      expect(loaded().refreshFailed).toBe(false);
    });

    it('a stale post success completes but is not applied', () => {
      createLoaded('ANSWERED');
      const post = send();
      advance(POLL_INTERVAL_MS); // this poll starts after the post
      expectGet().flush(ticket('CLOSED'));

      expectPost().flush(ticket('CUSTOMER_REPLIED'));
      expect(post.outcome).toBe('done');
      expect(loaded().ticket.status).toBe('CLOSED');
    });
  });

  describe('visibility', () => {
    it('a hidden tab sends no polls', () => {
      createLoaded();
      setVisibility('hidden');
      advance(20_000);
      expectNoRequest();
    });

    it('becoming visible sends one poll at once', () => {
      createLoaded();
      setVisibility('hidden');
      advance(10_000);

      setVisibility('visible');
      expectGet().flush(ticket('OPEN'));
      advance(POLL_INTERVAL_MS);
      expectGet().flush(ticket('OPEN'));
    });

    it('becoming visible while a poll is in flight sends no second request', () => {
      createLoaded();
      advance(POLL_INTERVAL_MS);
      const poll = expectGet();

      setVisibility('hidden');
      setVisibility('visible');
      expectNoRequest();

      poll.flush(ticket('OPEN'));
      advance(POLL_INTERVAL_MS);
      expectGet().flush(ticket('OPEN'));
    });

    it('a tab hidden at open loads once and polls only when visible', () => {
      visibility = 'hidden';
      createLoaded();
      advance(20_000);
      expectNoRequest();

      setVisibility('visible');
      expectGet().flush(ticket('OPEN'));
    });
  });

  describe('destroy', () => {
    it('cancels the in-flight poll and schedules nothing', () => {
      createLoaded();
      advance(POLL_INTERVAL_MS);
      const poll = expectGet();
      // Nothing is pending while the poll is in flight, so any timer counted below is the store's.
      expect(vi.getTimerCount()).toBe(0);

      fixture.destroy();
      expect(poll.cancelled).toBe(true);
      // A timer loop that re-arms itself sends no request after destroy, but still holds a timer.
      expect(vi.getTimerCount()).toBe(0);
      advance(10 * POLL_INTERVAL_MS);
      expect(vi.getTimerCount()).toBe(0);
      expectNoRequest();
    });

    it('after destroy, becoming visible sends nothing', () => {
      createLoaded();
      fixture.destroy();

      setVisibility('hidden');
      setVisibility('visible');
      advance(10_000);
      expectNoRequest();
    });
  });

  describe('thread request errors', () => {
    it('a poll 401 stops polling and keeps the thread without a note', () => {
      createLoaded();
      const before = loaded();
      advance(POLL_INTERVAL_MS);
      fail(expectGet(), 401);

      expect(store.state()).toBe(before);
      advance(20_000);
      expectNoRequest();
      expect(fixture.componentRef.hostView.destroyed).toBe(false);
    });

    it('a stale 401 still stops polling', () => {
      createLoaded();
      advance(POLL_INTERVAL_MS);
      const poll = expectGet();
      send();
      expectPost().flush(ticket('OPEN'));

      fail(poll, 401);
      advance(20_000);
      expectNoRequest();
    });

    for (const status of [404, 400]) {
      it(`a poll ${status} shows not found and stops polling`, () => {
        createLoaded();
        advance(POLL_INTERVAL_MS);
        fail(expectGet(), status);

        expect(store.state()).toEqual({ kind: 'not-found' });
        advance(20_000);
        expectNoRequest();
      });
    }

    it('a poll 5xx keeps the thread, sets the note, keeps polling, and the next success clears it', () => {
      createLoaded();
      advance(POLL_INTERVAL_MS);
      fail(expectGet(), 503);
      expect(loaded().refreshFailed).toBe(true);
      expect(loaded().ticket.status).toBe('OPEN');

      advance(POLL_INTERVAL_MS);
      expectGet().flush(ticket('ANSWERED'));
      expect(loaded().refreshFailed).toBe(false);
      expect(loaded().ticket.status).toBe('ANSWERED');
    });

    it('a failed first load starts no polling; a successful retry does', () => {
      create();
      fail(expectGet(), 500);
      expect(store.state()).toEqual({ kind: 'error', message: 'Failure 500.' });
      advance(20_000);
      expectNoRequest();

      store.retry();
      expect(store.state()).toEqual({ kind: 'loading' });
      expectGet().flush(ticket('OPEN'));
      expect(loaded().ticket.status).toBe('OPEN');
      advance(POLL_INTERVAL_MS);
      expectGet().flush(ticket('OPEN'));
    });

    it('a first-load 401 stays loading and never polls', () => {
      create();
      fail(expectGet(), 401);

      expect(store.state()).toEqual({ kind: 'loading' });
      advance(20_000);
      expectNoRequest();
    });
  });

  describe('post errors', () => {
    for (const status of [400, 500]) {
      it(`a post ${status} changes no thread state and sets no note`, () => {
        createLoaded();
        const before = loaded();
        advance(POLL_INTERVAL_MS);
        const poll = expectGet(); // started before the post
        const post = send();
        fail(expectPost(), status);

        expect(post.outcome).toBe(status);
        expect(store.state()).toBe(before);
        // The failed post did not count as applied, so the older poll still wins.
        poll.flush(ticket('ANSWERED'));
        expect(loaded().ticket.status).toBe('ANSWERED');
        advance(POLL_INTERVAL_MS);
        expectGet().flush(ticket('ANSWERED'));
      });
    }

    it('a post 404 shows not found and stops polling', () => {
      createLoaded();
      const post = send();
      fail(expectPost(), 404);

      expect(post.outcome).toBe(404);
      expect(store.state()).toEqual({ kind: 'not-found' });
      advance(20_000);
      expectNoRequest();
    });

    it('a post 401 stops polling', () => {
      createLoaded();
      const before = loaded();
      const post = send();
      fail(expectPost(), 401);

      expect(post.outcome).toBe(401);
      expect(store.state()).toBe(before);
      advance(20_000);
      expectNoRequest();
    });

    it('a post 409 sends a reload', () => {
      createLoaded();
      const post = send();
      fail(expectPost(), 409);

      expect(post.outcome).toBe(409);
      expectGet().flush(ticket('ANSWERED'));
      expect(loaded().ticket.status).toBe('ANSWERED');
    });
  });
});
