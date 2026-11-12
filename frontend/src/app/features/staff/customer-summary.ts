import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButton } from '@angular/material/button';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
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
import { RouterLink } from '@angular/router';
import { Subject, catchError, map, of, switchMap } from 'rxjs';
import { GENERIC_ERROR_MESSAGE, toProblem } from '../../core/http/problem-detail';
import { AgentApi } from './agent-api';
import { AgentCustomerSummary } from './agent-ticket';

type SummaryState =
  | { kind: 'loading' }
  | { kind: 'loaded'; customers: AgentCustomerSummary[] }
  | { kind: 'error'; message: string };

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButton,
    MatCell,
    MatCellDef,
    MatColumnDef,
    MatHeaderCell,
    MatHeaderCellDef,
    MatHeaderRow,
    MatHeaderRowDef,
    MatProgressSpinner,
    MatRow,
    MatRowDef,
    MatTable,
    RouterLink,
  ],
  selector: 'app-customer-summary',
  template: `
    <h1>Customers</h1>

    @let current = state();
    @if (current.kind === 'loading') {
      <mat-progress-spinner mode="indeterminate" diameter="40" aria-label="Loading customers" />
    } @else if (current.kind === 'error') {
      <div class="error">
        <p role="alert">{{ current.message }}</p>
        <button mat-button type="button" (click)="retry()">Retry</button>
      </div>
    } @else if (current.customers.length === 0) {
      <p class="empty">No customers with tickets yet</p>
    } @else {
      <!-- Server order (NFR-05); the client never sorts. -->
      <table mat-table [dataSource]="current.customers">
        <ng-container matColumnDef="name">
          <th mat-header-cell *matHeaderCellDef>Customer</th>
          <!-- A link, not a row click handler: keyboard and "open in a new tab" work. -->
          <td mat-cell *matCellDef="let customer">
            <a [routerLink]="['/staff/customers', customer.id]">{{ customer.name }}</a>
          </td>
        </ng-container>
        <ng-container matColumnDef="email">
          <th mat-header-cell *matHeaderCellDef>Email</th>
          <td mat-cell *matCellDef="let customer">{{ customer.email }}</td>
        </ng-container>
        <ng-container matColumnDef="ticketCount">
          <th mat-header-cell *matHeaderCellDef>Tickets</th>
          <td mat-cell *matCellDef="let customer">{{ customer.ticketCount }}</td>
        </ng-container>
        <ng-container matColumnDef="activeTicketCount">
          <th mat-header-cell *matHeaderCellDef>Active</th>
          <td mat-cell *matCellDef="let customer">{{ customer.activeTicketCount }}</td>
        </ng-container>
        <ng-container matColumnDef="lastMessageAt">
          <th mat-header-cell *matHeaderCellDef>Last activity</th>
          <td mat-cell *matCellDef="let customer">{{ customer.lastMessageAt | date: 'medium' }}</td>
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
    mat-progress-spinner {
      margin: 24px auto;
    }
    .error p {
      color: var(--mat-sys-error);
    }
  `,
})
export class CustomerSummary {
  private readonly api = inject(AgentApi);

  protected readonly columns = [
    'name',
    'email',
    'ticketCount',
    'activeTicketCount',
    'lastMessageAt',
  ];
  protected readonly state = signal<SummaryState>({ kind: 'loading' });

  private readonly load = new Subject<void>();

  constructor() {
    this.load
      .pipe(
        switchMap(() => {
          this.state.set({ kind: 'loading' });
          return this.api.customers().pipe(
            map((customers): SummaryState => ({ kind: 'loaded', customers })),
            catchError((error: HttpErrorResponse) =>
              of<SummaryState>({
                kind: 'error',
                message: toProblem(error).detail ?? GENERIC_ERROR_MESSAGE,
              }),
            ),
          );
        }),
        takeUntilDestroyed(),
      )
      .subscribe((state) => this.state.set(state));
    this.load.next();
  }

  protected retry(): void {
    this.load.next();
  }
}
