import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { ErrorStateMatcher } from '@angular/material/core';
import { MatFormField, MatHint, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { UserRole } from '../../core/auth/user-role';
import { GENERIC_ERROR_MESSAGE, toProblem } from '../../core/http/problem-detail';
import { isJavaBlank } from '../../shared/text/java-whitespace';
import { CATEGORY_LABELS, STATUS_LABELS } from '../../shared/ticket/ticket-labels';
import { AgentTicketResponse } from './agent-ticket';
import { AgentThreadStore } from './agent-thread-store';

// BR-11: at most 4000 characters counted as typed (UTF-16 code units, ADR-015 §6).
const BODY_MAX_LENGTH = 4000;

const ROLE_LABELS: Record<UserRole, string> = {
  CUSTOMER: 'Customer',
  AGENT: 'Agent',
};

// Blank by Java's rules, as the server checks it (ADR-015 §5).
function notJavaBlank(control: AbstractControl<string>): ValidationErrors | null {
  return isJavaBlank(control.value) ? { blank: true } : null;
}

// Invalid input is never shown as an error, neither after blur nor after a send clears the text
// (the form stays submitted). Only the disabled button and the count reflect it (ADR-015 §6).
const NEVER_AN_ERROR: ErrorStateMatcher = { isErrorState: () => false };

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    MatButton,
    MatFormField,
    MatHint,
    MatInput,
    MatLabel,
    MatProgressSpinner,
    ReactiveFormsModule,
    RouterLink,
  ],
  providers: [AgentThreadStore],
  selector: 'app-agent-ticket-thread',
  template: `
    @let current = state();
    @if (current.kind === 'loading') {
      <mat-progress-spinner mode="indeterminate" diameter="40" aria-label="Loading conversation" />
    } @else if (current.kind === 'error') {
      <div class="error">
        <p role="alert">{{ current.message }}</p>
        <button mat-button type="button" (click)="retry()">Retry</button>
      </div>
    } @else if (current.kind === 'not-found') {
      <div class="not-found">
        <h1>Ticket not found</h1>
        <a mat-button routerLink="/staff">Back to the queue</a>
      </div>
    } @else {
      @let ticket = current.ticket;
      @let closed = ticket.status === 'CLOSED';
      <header>
        <h1>{{ ticket.subject }}</h1>
        <p class="details">
          <span class="status">{{ statusLabels[ticket.status] }}</span>
          <span class="category">{{ categoryLabels[ticket.category] }}</span>
          @if (ticket.referenceNumber !== null) {
            <span class="reference">Ref. {{ ticket.referenceNumber }}</span>
          }
          <span class="created">{{ ticket.createdAt | date: 'medium' }}</span>
        </p>
        <p class="people">
          <span class="customer">
            <a [routerLink]="['/staff/customers', ticket.customer.id]">{{
              ticket.customer.name
            }}</a>
            <span class="email">{{ ticket.customer.email }}</span>
          </span>
          <span class="assignee">{{ assigneeLabel(ticket) }}</span>
        </p>
      </header>
      @if (current.refreshFailed) {
        <p class="note" role="status">Cannot refresh the conversation</p>
      }
      <!-- Server order (NFR-05); the client never sorts. No "You": the author has no id. -->
      <ol class="messages">
        @for (message of ticket.messages; track message.id) {
          <li
            class="message"
            [class.customer]="message.author.role === 'CUSTOMER'"
            [class.agent]="message.author.role === 'AGENT'"
          >
            <p class="meta">
              <span class="author"
                >{{ message.author.name }} · {{ roleLabels[message.author.role] }}</span
              >
              <span>{{ message.createdAt | date: 'medium' }}</span>
            </p>
            <!-- Interpolation only, never innerHTML (ADR-015 §6). -->
            <p class="body">{{ message.body }}</p>
          </li>
        }
      </ol>
      <form [formGroup]="form" (ngSubmit)="send()">
        <mat-form-field>
          <mat-label>Message</mat-label>
          <!-- No maxlength attribute: the browser would silently cut pasted text. -->
          <!-- Editable on a CLOSED ticket, so text typed while a poll brings CLOSED is kept. -->
          <textarea
            matInput
            formControlName="body"
            rows="4"
            [errorStateMatcher]="neverAnError"
          ></textarea>
          @if (closed) {
            <mat-hint class="closed">This ticket is closed. Reopen it to reply.</mat-hint>
          }
          <mat-hint align="end" class="count">{{ body().length }} / {{ bodyMaxLength }}</mat-hint>
        </mat-form-field>
        @if (postError(); as message) {
          <p class="post-error" role="alert">{{ message }}</p>
        }
        <button mat-flat-button type="submit" [disabled]="form.invalid || posting() || closed">
          Send
        </button>
      </form>
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
    .details,
    .people,
    .meta {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
    }
    .customer {
      display: flex;
      gap: 8px;
    }
    .status,
    .author {
      font-weight: 500;
    }
    .email {
      color: var(--mat-sys-on-surface-variant);
    }
    .error p,
    .post-error {
      color: var(--mat-sys-error);
    }
    .note {
      color: var(--mat-sys-on-surface-variant);
    }
    .messages {
      display: flex;
      flex-direction: column;
      gap: 12px;
      list-style: none;
      padding: 0;
    }
    .message {
      max-width: 80%;
      padding: 8px 16px;
      border-radius: 12px;
    }
    .message.agent {
      align-self: flex-end;
      background: var(--mat-sys-primary-container);
      color: var(--mat-sys-on-primary-container);
    }
    .message.customer {
      align-self: flex-start;
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface);
    }
    .meta {
      margin: 0 0 4px;
      font-size: 0.875em;
    }
    .body {
      margin: 0;
      white-space: pre-wrap;
    }
    form {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 8px;
    }
    mat-form-field {
      width: 100%;
    }
  `,
})
export class AgentTicketThread {
  private readonly store = inject(AgentThreadStore);
  private readonly auth = inject(AuthService);

  protected readonly statusLabels = STATUS_LABELS;
  protected readonly categoryLabels = CATEGORY_LABELS;
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly bodyMaxLength = BODY_MAX_LENGTH;
  protected readonly neverAnError = NEVER_AN_ERROR;

  protected readonly state = this.store.state;

  protected readonly form = inject(NonNullableFormBuilder).group({
    body: ['', [notJavaBlank, Validators.maxLength(BODY_MAX_LENGTH)]],
  });
  protected readonly body = toSignal(this.form.controls.body.valueChanges, { initialValue: '' });
  protected readonly posting = signal(false);
  protected readonly postError = signal<string | null>(null);

  constructor() {
    // Read once: the UI never reuses this page for another id (ADR-016 §3). The id stays a string.
    this.store.start(inject(ActivatedRoute).snapshot.paramMap.get('id')!);
    // The post error stays until the user edits the text or sends again (ADR-015 §6).
    this.form.controls.body.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.postError.set(null));
  }

  send(): void {
    // The disabled attribute updates only on the next render, so a second submit can arrive first.
    if (this.posting() || this.form.invalid) {
      return;
    }
    this.postError.set(null);
    this.posting.set(true);
    this.store
      .send(this.form.controls.body.value)
      .pipe(finalize(() => this.posting.set(false)))
      .subscribe({
        // The post's own response decides, even when its thread is stale and not applied.
        next: () => this.form.reset(),
        error: (error: HttpErrorResponse) => {
          // A 401 ends the session through the interceptor; the page shows nothing (ADR-014).
          if (error.status !== 401) {
            this.postError.set(toProblem(error).detail ?? GENERIC_ERROR_MESSAGE);
          }
        },
      });
  }

  protected retry(): void {
    this.store.retry();
  }

  // " (you)" by id, as in the queue's assignee select (ADR-016 §2).
  protected assigneeLabel(ticket: AgentTicketResponse): string {
    const assignee = ticket.assignee;
    if (assignee === null) {
      return 'Unassigned';
    }
    const you = assignee.id === this.auth.user()?.id ? ' (you)' : '';
    return `Assigned to ${assignee.name}${you}`;
  }
}
