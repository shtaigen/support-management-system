import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButton } from '@angular/material/button';
import { MatButtonToggle, MatButtonToggleGroup } from '@angular/material/button-toggle';
import { MatFormField, MatLabel } from '@angular/material/form-field';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { MatOption, MatSelect } from '@angular/material/select';
import {
  MatCell,
  MatCellDef,
  MatColumnDef,
  MatHeaderCell,
  MatHeaderCellDef,
  MatHeaderRow,
  MatHeaderRowDef,
  MatRow,
  MatRowDef,
  MatTable,
} from '@angular/material/table';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subject, catchError, map, of, switchMap } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { GENERIC_ERROR_MESSAGE, toProblem } from '../../core/http/problem-detail';
import { TicketStatus } from '../../shared/ticket/ticket';
import { CATEGORY_LABELS, STATUS_LABELS } from '../../shared/ticket/ticket-labels';
import { AgentApi } from './agent-api';
import { AgentRef, AgentTicketSummary } from './agent-ticket';
import { AssigneeKey, StatusKey, toTicketFilter } from './queue-filter';

type QueueState =
  | { kind: 'loading' }
  | { kind: 'loaded'; tickets: AgentTicketSummary[] }
  | { kind: 'error'; message: string }
  // A customer's page only: the id is not a customer with tickets (ADR-016 §2).
  | { kind: 'not-found' };

const STATUS_FILTERS: { label: string; key: StatusKey }[] = [
  { label: 'Active', key: 'active' },
  { label: 'All', key: 'all' },
  ...(Object.keys(STATUS_LABELS) as TicketStatus[]).map((status) => ({
    label: STATUS_LABELS[status],
    key: status,
  })),
];

const COLUMNS = [
  'subject',
  'customer',
  'category',
  'referenceNumber',
  'status',
  'assignee',
  'lastMessageAt',
];

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButton,
    MatButtonToggle,
    MatButtonToggleGroup,
    MatCell,
    MatCellDef,
    MatColumnDef,
    MatFormField,
    MatHeaderCell,
    MatHeaderCellDef,
    MatHeaderRow,
    MatHeaderRowDef,
    MatLabel,
    MatOption,
    MatProgressSpinner,
    MatRow,
    MatRowDef,
    MatSelect,
    MatTable,
    RouterLink,
  ],
  selector: 'app-ticket-queue',
  template: `
    @if (customerId === null) {
      <h1>Ticket queue</h1>
    } @else {
      <header class="customer">
        <a mat-button routerLink="/staff/customers">All customers</a>
        @if (customer(); as known) {
          <h1>{{ known.name }}</h1>
          <p class="email">{{ known.email }}</p>
        } @else {
          <h1>Customer tickets</h1>
        }
      </header>
    }

    <div class="filters">
      <mat-button-toggle-group aria-label="Filter by status" hideSingleSelectionIndicator>
        @for (filter of statusFilters; track filter.key) {
          <mat-button-toggle
            [checked]="status() === filter.key"
            (change)="selectStatus(filter.key)"
            >{{ filter.label }}</mat-button-toggle
          >
        }
      </mat-button-toggle-group>
      <mat-form-field subscriptSizing="dynamic">
        <mat-label>Assignee</mat-label>
        <mat-select [value]="assignee()" (selectionChange)="selectAssignee($event.value)">
          <mat-option value="anyone">Anyone</mat-option>
          <mat-option value="unassigned">Unassigned</mat-option>
          <!-- Server order (NFR-05); the client never sorts. -->
          @for (option of agentOptions(); track option.key) {
            <mat-option [value]="option.key">{{ option.label }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      @if (agentsFailed()) {
        <p class="note">Cannot load agents</p>
      }
    </div>

    @let current = state();
    @if (current.kind === 'loading') {
      <mat-progress-spinner mode="indeterminate" diameter="40" aria-label="Loading tickets" />
    } @else if (current.kind === 'error') {
      <div class="error">
        <p role="alert">{{ current.message }}</p>
        <button mat-button type="button" (click)="retry()">Retry</button>
      </div>
    } @else if (current.kind === 'not-found') {
      <p class="empty">No tickets for this customer</p>
    } @else if (current.tickets.length === 0) {
      <p class="empty">{{ emptyText() }}</p>
    } @else {
      <!-- Server order (NFR-05); the client never sorts. -->
      <table mat-table [dataSource]="current.tickets">
        <ng-container matColumnDef="subject">
          <th mat-header-cell *matHeaderCellDef>Subject</th>
          <!-- A link, not a row click handler: keyboard and "open in a new tab" work. -->
          <td mat-cell *matCellDef="let ticket">
            <a [routerLink]="['/staff/tickets', ticket.id]">{{ ticket.subject }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="customer">
          <th mat-header-cell *matHeaderCellDef>Customer</th>
          <td mat-cell *matCellDef="let ticket">
            <span class="name">{{ ticket.customer.name }}</span>
            <span class="email">{{ ticket.customer.email }}</span>
          </td>
        </ng-container>
        <ng-container matColumnDef="category">
          <th mat-header-cell *matHeaderCellDef>Category</th>
          <td mat-cell *matCellDef="let ticket">{{ categoryLabel(ticket) }}</td>
        </ng-container>
        <ng-container matColumnDef="referenceNumber">
          <th mat-header-cell *matHeaderCellDef>Ref.</th>
          <td mat-cell *matCellDef="let ticket">{{ ticket.referenceNumber ?? '' }}</td>
        </ng-container>
        <ng-container matColumnDef="status">
          <th mat-header-cell *matHeaderCellDef>Status</th>
          <td mat-cell *matCellDef="let ticket">{{ statusLabel(ticket) }}</td>
        </ng-container>
        <ng-container matColumnDef="assignee">
          <th mat-header-cell *matHeaderCellDef>Assignee</th>
          <td mat-cell *matCellDef="let ticket">{{ ticket.assignee?.name ?? 'Unassigned' }}</td>
        </ng-container>
        <ng-container matColumnDef="lastMessageAt">
          <th mat-header-cell *matHeaderCellDef>Last activity</th>
          <td mat-cell *matCellDef="let ticket">{{ ticket.lastMessageAt | date: 'medium' }}</td>
        </ng-container>
        <tr mat-header-row *matHeaderRowDef="columns"></tr>
        <tr mat-row *matRowDef="let row; columns: columns"></tr>
      </table>
    }
  `,
  styles: `
    :host {
      display: block;
      padding: 24px;
    }
    .customer h1 {
      margin-bottom: 0;
    }
    .email {
      color: var(--mat-sys-on-surface-variant);
    }
    td .name,
    td .email {
      display: block;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 16px;
      margin-bottom: 16px;
    }
    .note {
      color: var(--mat-sys-error);
    }
    mat-progress-spinner {
      margin: 24px auto;
    }
    .error p {
      color: var(--mat-sys-error);
    }
  `,
})
export class TicketQueue {
  private readonly api = inject(AgentApi);
  private readonly auth = inject(AuthService);

  // Read once and kept as a string: never converted to a number (ADR-016 §2, ADR-015 §3).
  protected readonly customerId = inject(ActivatedRoute).snapshot.paramMap.get('customerId');

  protected readonly statusFilters = STATUS_FILTERS;
  // The customer is in the heading on a customer's page.
  protected readonly columns =
    this.customerId === null ? COLUMNS : COLUMNS.filter((column) => column !== 'customer');

  // Defaults: Active in the queue, All on a customer's page (ADR-016 §2).
  private readonly defaultStatus: StatusKey = this.customerId === null ? 'active' : 'all';
  protected readonly status = signal<StatusKey>(this.defaultStatus);
  protected readonly assignee = signal<AssigneeKey>('anyone');
  protected readonly state = signal<QueueState>({ kind: 'loading' });
  protected readonly customer = signal<{ name: string; email: string } | null>(null);

  private readonly agents = signal<AgentRef[]>([]);
  protected readonly agentsFailed = signal(false);
  protected readonly agentOptions = computed(() =>
    this.agents().map((agent) => ({
      key: `agent:${agent.id}` as const,
      label: agent.id === this.auth.user()?.id ? `${agent.name} (you)` : agent.name,
    })),
  );

  protected readonly emptyText = computed(() => {
    if (this.status() !== 'all' || this.assignee() !== 'anyone') {
      return 'No tickets match these filters';
    }
    return this.customerId === null ? 'No tickets yet' : 'No tickets for this customer';
  });

  private readonly load = new Subject<void>();

  constructor() {
    this.load
      .pipe(
        // switchMap cancels the pending request, so only the current filters' response is shown.
        switchMap(() => {
          this.state.set({ kind: 'loading' });
          const filter = toTicketFilter(this.status(), this.assignee(), this.customerId ?? undefined);
          return this.api.tickets(filter).pipe(
            map((tickets): QueueState => ({ kind: 'loaded', tickets })),
            catchError((error: HttpErrorResponse) => of(this.toErrorState(error))),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((state) => {
        this.state.set(state);
        // Kept from the first response with rows, also after a later empty one.
        if (state.kind === 'loaded' && state.tickets.length > 0 && this.customer() === null) {
          const { name, email } = state.tickets[0].customer;
          this.customer.set({ name, email });
        }
      });
    this.load.next();

    // Independent of the queue: a failure leaves the two fixed options (ADR-016 §2).
    this.api
      .agents()
      .pipe(takeUntilDestroyed())
      .subscribe({
        next: (agents) => this.agents.set(agents),
        error: () => this.agentsFailed.set(true),
      });
  }

  protected selectStatus(key: StatusKey): void {
    // A click on the already checked toggle still emits change.
    if (key === this.status()) {
      return;
    }
    this.status.set(key);
    this.load.next();
  }

  // mat-select emits nothing when the selected option is chosen again.
  protected selectAssignee(key: AssigneeKey): void {
    this.assignee.set(key);
    this.load.next();
  }

  protected retry(): void {
    this.load.next();
  }

  // Typed lookups: mat-table cell templates see their row as any.
  protected statusLabel(ticket: AgentTicketSummary): string {
    return STATUS_LABELS[ticket.status];
  }

  protected categoryLabel(ticket: AgentTicketSummary): string {
    return CATEGORY_LABELS[ticket.category];
  }

  // The filters the UI sends are always valid, so a 400 means the customer id is not a number.
  private toErrorState(error: HttpErrorResponse): QueueState {
    if (this.customerId !== null && error.status === 400) {
      return { kind: 'not-found' };
    }
    return { kind: 'error', message: toProblem(error).detail ?? GENERIC_ERROR_MESSAGE };
  }
}
