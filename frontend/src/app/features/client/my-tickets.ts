import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButton } from '@angular/material/button';
import { MatButtonToggle, MatButtonToggleGroup } from '@angular/material/button-toggle';
import { MatListItem, MatListItemLine, MatListItemTitle, MatNavList } from '@angular/material/list';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { RouterLink } from '@angular/router';
import { Subject, catchError, map, of, switchMap } from 'rxjs';
import { GENERIC_ERROR_MESSAGE, toProblem } from '../../core/http/problem-detail';
import { CustomerTicketSummary, TicketStatus } from './customer-ticket';
import { CustomerTicketsApi } from './customer-tickets-api';
import { CATEGORY_LABELS, STATUS_LABELS } from './ticket-labels';

type ListState =
  | { kind: 'loading' }
  | { kind: 'loaded'; tickets: CustomerTicketSummary[] }
  | { kind: 'error'; message: string };

// null is "All": no status parameter is sent (ADR-015 §7).
const FILTERS: { label: string; status: TicketStatus | null }[] = [
  { label: 'All', status: null },
  ...(Object.keys(STATUS_LABELS) as TicketStatus[]).map((status) => ({
    label: STATUS_LABELS[status],
    status,
  })),
];

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButton,
    MatButtonToggle,
    MatButtonToggleGroup,
    MatListItem,
    MatListItemLine,
    MatListItemTitle,
    MatNavList,
    MatProgressSpinner,
    RouterLink,
  ],
  selector: 'app-my-tickets',
  template: `
    <header>
      <h1>My tickets</h1>
      <a mat-flat-button routerLink="/client/tickets/new">New ticket</a>
    </header>
    <!-- Per-toggle checked/change: the group's value matching ignores a null value. -->
    <mat-button-toggle-group aria-label="Filter by status" hideSingleSelectionIndicator>
      @for (filter of filters; track filter.label) {
        <mat-button-toggle
          [checked]="status() === filter.status"
          (change)="select(filter.status)"
          >{{ filter.label }}</mat-button-toggle
        >
      }
    </mat-button-toggle-group>

    @let current = state();
    @if (current.kind === 'loading') {
      <mat-progress-spinner mode="indeterminate" diameter="40" aria-label="Loading tickets" />
    } @else if (current.kind === 'error') {
      <div class="error">
        <p role="alert">{{ current.message }}</p>
        <button mat-button type="button" (click)="retry()">Retry</button>
      </div>
    } @else if (current.tickets.length === 0) {
      @if (status() === null) {
        <div class="empty">
          <p>No tickets yet</p>
          <a mat-flat-button routerLink="/client/tickets/new">New ticket</a>
        </div>
      } @else {
        <p class="empty">No tickets with this status</p>
      }
    } @else {
      <!-- Server order (NFR-05); the client never sorts. -->
      <mat-nav-list>
        @for (ticket of current.tickets; track ticket.id) {
          <a mat-list-item [routerLink]="['/client/tickets', ticket.id]">
            <span matListItemTitle>{{ ticket.subject }}</span>
            <span matListItemLine class="details">
              <span>{{ categoryLabels[ticket.category] }}</span>
              @if (ticket.referenceNumber !== null) {
                <span class="reference">Ref. {{ ticket.referenceNumber }}</span>
              }
              <span class="status">{{ statusLabels[ticket.status] }}</span>
              <span>{{ ticket.lastMessageAt | date: 'medium' }}</span>
            </span>
          </a>
        }
      </mat-nav-list>
    }
  `,
  styles: `
    :host {
      display: block;
      padding: 24px;
    }
    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
    }
    mat-button-toggle-group {
      margin-bottom: 16px;
    }
    mat-progress-spinner {
      margin: 24px auto;
    }
    .details {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
    }
    .status {
      font-weight: 500;
    }
    .error p {
      color: var(--mat-sys-error);
    }
  `,
})
export class MyTickets {
  private readonly api = inject(CustomerTicketsApi);

  protected readonly filters = FILTERS;
  protected readonly statusLabels = STATUS_LABELS;
  protected readonly categoryLabels = CATEGORY_LABELS;

  protected readonly status = signal<TicketStatus | null>(null);
  protected readonly state = signal<ListState>({ kind: 'loading' });

  private readonly load = new Subject<TicketStatus | null>();

  constructor() {
    this.load
      .pipe(
        // switchMap cancels the pending request, so only the current filter's response is shown.
        switchMap((status) => {
          // Every load replaces the old rows with the spinner, not only the first one.
          this.state.set({ kind: 'loading' });
          return this.api.list(status ?? undefined).pipe(
            map((tickets): ListState => ({ kind: 'loaded', tickets })),
            catchError((error: HttpErrorResponse) =>
              of<ListState>({
                kind: 'error',
                message: toProblem(error).detail ?? GENERIC_ERROR_MESSAGE,
              }),
            ),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((state) => this.state.set(state));
    this.load.next(null);
  }

  protected select(status: TicketStatus | null): void {
    // A click on the already checked toggle still emits change.
    if (status === this.status()) {
      return;
    }
    this.status.set(status);
    this.load.next(status);
  }

  protected retry(): void {
    this.load.next(this.status());
  }
}
