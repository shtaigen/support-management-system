import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT, DestroyRef, Injectable, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, defer, finalize, map, tap } from 'rxjs';
import { GENERIC_ERROR_MESSAGE, toProblem } from '../../core/http/problem-detail';
import { AgentApi } from './agent-api';
import { AgentTicketResponse } from './agent-ticket';

export type AgentThreadState =
  // First load or Retry pending; also kept after a first-load 401 (ADR-015 §4).
  | { kind: 'loading' }
  // First load failed with anything but 400/404/401.
  | { kind: 'error'; message: string }
  | { kind: 'not-found' }
  | { kind: 'loaded'; ticket: AgentTicketResponse; refreshFailed: boolean };

// The agent zone's own constant, with the customer thread's value (NFR-06, ADR-016 §3).
export const POLL_INTERVAL_MS = 4000;

// The agent zone's own copy of the customer TicketThreadStore (ADR-016 §3): loading, polling and
// sequence numbers of one thread page (ADR-015 §4). Provided by the page, so DestroyRef is the
// page's own and polling ends with it.
@Injectable()
export class AgentThreadStore {
  private readonly api = inject(AgentApi);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  private readonly current = signal<AgentThreadState>({ kind: 'loading' });
  readonly state = this.current.asReadonly();

  private id = '';
  // Every thread request takes a number when it starts; only a newer response is applied.
  private nextSeq = 0;
  private lastApplied = 0;
  private pollingStarted = false;
  private active = false;
  private destroyed = false;
  private pollInFlight = false;
  private timer: ReturnType<typeof setTimeout> | undefined;

  private readonly onVisibilityChange = (): void => {
    if (this.document.visibilityState === 'hidden') {
      // A poll in flight still completes and is applied; its completion does not schedule.
      clearTimeout(this.timer);
    } else if (this.active && !this.pollInFlight) {
      clearTimeout(this.timer);
      this.poll();
    }
  };

  constructor() {
    this.document.addEventListener('visibilitychange', this.onVisibilityChange);
    // Registered before any request, so this runs before takeUntilDestroyed cancels them and
    // their finalize finds the store destroyed.
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      this.stop();
      this.document.removeEventListener('visibilitychange', this.onVisibilityChange);
    });
  }

  // The id stays a string (ADR-015 §3).
  start(id: string): void {
    this.id = id;
    this.load();
  }

  retry(): void {
    this.current.set({ kind: 'loading' });
    this.load();
  }

  // Completes or errors with the post's own response, applied or not, so the composer outcome
  // follows it (ADR-015 §6). Errors other than 404/401/409 never touch the thread. A 409 means a
  // CLOSED ticket (BR-06) or a concurrent change (BR-15); both reload.
  send(body: string): Observable<void> {
    return defer(() => {
      const seq = ++this.nextSeq;
      return this.api.postMessage(this.id, body).pipe(
        takeUntilDestroyed(this.destroyRef),
        tap({
          next: (ticket) => this.apply(seq, () => this.applyTicket(ticket)),
          error: (error: HttpErrorResponse) => {
            if (error.status === 401) {
              this.stop();
            } else if (error.status === 404) {
              this.apply(seq, () => this.notFound());
            } else if (error.status === 409) {
              this.load();
            }
          },
        }),
        map(() => undefined),
      );
    });
  }

  // First load, Retry and the reload after a 409. Never schedules a poll itself; only the
  // first applied success starts polling.
  private load(): void {
    const seq = ++this.nextSeq;
    this.api
      .ticket(this.id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (ticket) => this.apply(seq, () => this.applyTicket(ticket)),
        error: (error: HttpErrorResponse) => this.handleError(seq, error),
      });
  }

  private poll(): void {
    this.pollInFlight = true;
    const seq = ++this.nextSeq;
    this.api
      .ticket(this.id)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        // Runs after the handlers and also on cancellation; schedule() checks the state then.
        finalize(() => {
          this.pollInFlight = false;
          this.schedule();
        }),
      )
      .subscribe({
        next: (ticket) => this.apply(seq, () => this.applyTicket(ticket)),
        error: (error: HttpErrorResponse) => this.handleError(seq, error),
      });
  }

  private schedule(): void {
    if (!this.active || this.destroyed || this.document.visibilityState === 'hidden') {
      return;
    }
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.poll(), POLL_INTERVAL_MS);
  }

  private stop(): void {
    this.active = false;
    clearTimeout(this.timer);
  }

  // A stale response, success or error, is ignored completely.
  private apply(seq: number, change: () => void): void {
    if (seq > this.lastApplied) {
      this.lastApplied = seq;
      change();
    }
  }

  private applyTicket(ticket: AgentTicketResponse): void {
    this.current.set({ kind: 'loaded', ticket, refreshFailed: false });
    if (!this.pollingStarted) {
      this.pollingStarted = true;
      this.active = true;
      this.schedule();
    }
  }

  private notFound(): void {
    this.current.set({ kind: 'not-found' });
    this.stop();
  }

  private handleError(seq: number, error: HttpErrorResponse): void {
    // The session is over (the interceptor logs out), so even a stale 401 stops polling.
    if (error.status === 401) {
      this.stop();
      return;
    }
    this.apply(seq, () => {
      const state = this.current();
      if (error.status === 400 || error.status === 404) {
        this.notFound();
      } else if (state.kind === 'loaded') {
        this.current.set({ ...state, refreshFailed: true });
      } else if (state.kind === 'loading') {
        this.current.set({
          kind: 'error',
          message: toProblem(error).detail ?? GENERIC_ERROR_MESSAGE,
        });
      }
    });
  }
}
