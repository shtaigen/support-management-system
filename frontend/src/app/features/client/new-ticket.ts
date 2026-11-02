import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatError, MatFormField, MatHint, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { MatOption, MatSelect } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { GENERIC_ERROR_MESSAGE, toProblem } from '../../core/http/problem-detail';
import { CreateTicketRequest, TicketCategory } from './customer-ticket';
import { CustomerTicketsApi } from './customer-tickets-api';
import { isJavaBlank, javaStrip } from './java-whitespace';
import { CATEGORY_LABELS } from './ticket-labels';

// FR-03, BR-11, BR-18. Lengths are UTF-16 code units, as Java's String.length() (ADR-015 §5).
const SUBJECT_MAX_LENGTH = 200;
const MESSAGE_MAX_LENGTH = 4000;
const REFERENCE_NUMBER = /^[A-Za-z0-9-]{1,50}$/;

// Control names equal the request's JSON field names, so errors[].field names a control.
type Field = keyof CreateTicketRequest;

// Blank by Java's rules, as the server checks it (ADR-015 §5).
function notJavaBlank(control: AbstractControl<string>): ValidationErrors | null {
  return isJavaBlank(control.value) ? { blank: true } : null;
}

// The subject is stripped before its length is checked; the message is counted as typed.
function subjectMaxLength(control: AbstractControl<string>): ValidationErrors | null {
  return javaStrip(control.value).length > SUBJECT_MAX_LENGTH ? { maxlength: true } : null;
}

// A blank reference number counts as absent (FR-03); otherwise BR-18 applies after stripping.
function referenceNumberFormat(control: AbstractControl<string>): ValidationErrors | null {
  const value = control.value;
  return isJavaBlank(value) || REFERENCE_NUMBER.test(javaStrip(value)) ? null : { format: true };
}

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    MatButton,
    MatError,
    MatFormField,
    MatHint,
    MatInput,
    MatLabel,
    MatOption,
    MatProgressSpinner,
    MatSelect,
    ReactiveFormsModule,
    RouterLink,
  ],
  selector: 'app-new-ticket',
  template: `
    <h1>New ticket</h1>
    <a mat-button routerLink="/client">Back to my tickets</a>
    @if (formError(); as message) {
      <p class="form-error" role="alert">{{ message }}</p>
    }
    @let controls = form.controls;
    <form [formGroup]="form" (ngSubmit)="submit()">
      <!-- One error per control: the server error first, then required/blank, then length or
           format (ADR-015 §5). Material shows it only when invalid && (touched || submitted). -->
      <mat-form-field class="subject">
        <mat-label>Subject</mat-label>
        <input matInput formControlName="subject" />
        @if (controls.subject.hasError('server')) {
          <mat-error>{{ controls.subject.getError('server') }}</mat-error>
        } @else if (controls.subject.hasError('blank')) {
          <mat-error>Enter a subject.</mat-error>
        } @else if (controls.subject.hasError('maxlength')) {
          <mat-error>Use at most {{ subjectMaxLength }} characters.</mat-error>
        }
      </mat-form-field>
      <mat-form-field class="category">
        <mat-label>Category</mat-label>
        <mat-select formControlName="category">
          @for (category of categories; track category) {
            <mat-option [value]="category">{{ categoryLabels[category] }}</mat-option>
          }
        </mat-select>
        @if (controls.category.hasError('server')) {
          <mat-error>{{ controls.category.getError('server') }}</mat-error>
        } @else if (controls.category.hasError('required')) {
          <mat-error>Choose a category.</mat-error>
        }
      </mat-form-field>
      <mat-form-field class="reference-number">
        <mat-label>Reference number (optional)</mat-label>
        <input matInput formControlName="referenceNumber" />
        @if (controls.referenceNumber.hasError('server')) {
          <mat-error>{{ controls.referenceNumber.getError('server') }}</mat-error>
        } @else if (controls.referenceNumber.hasError('format')) {
          <mat-error>Use 1–50 letters, digits or hyphens.</mat-error>
        }
      </mat-form-field>
      <mat-form-field class="message">
        <mat-label>Message</mat-label>
        <!-- No maxlength attribute: the browser would silently cut pasted text. -->
        <textarea matInput formControlName="message" rows="6"></textarea>
        <mat-hint align="end" class="count">{{ message().length }} / {{ messageMaxLength }}</mat-hint>
        @if (controls.message.hasError('server')) {
          <mat-error>{{ controls.message.getError('server') }}</mat-error>
        } @else if (controls.message.hasError('blank')) {
          <mat-error>Enter a message.</mat-error>
        } @else if (controls.message.hasError('maxlength')) {
          <mat-error>Use at most {{ messageMaxLength }} characters.</mat-error>
        }
      </mat-form-field>
      <!-- Disabled only while submitting: an invalid form must stay submittable to show its errors. -->
      <button mat-flat-button type="submit" [disabled]="submitting()">
        @if (submitting()) {
          <mat-progress-spinner mode="indeterminate" diameter="20" />
        }
        Create ticket
      </button>
    </form>
  `,
  styles: `
    :host {
      display: block;
      padding: 24px;
    }
    form {
      display: flex;
      flex-direction: column;
      align-items: flex-start;
      gap: 8px;
      max-width: 640px;
      margin-top: 16px;
    }
    mat-form-field {
      width: 100%;
    }
    .form-error {
      color: var(--mat-sys-error);
    }
    mat-progress-spinner {
      display: inline-block;
      margin-right: 8px;
      vertical-align: middle;
    }
  `,
})
export class NewTicket {
  private readonly api = inject(CustomerTicketsApi);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);

  protected readonly categories = Object.keys(CATEGORY_LABELS) as TicketCategory[];
  protected readonly categoryLabels = CATEGORY_LABELS;
  protected readonly subjectMaxLength = SUBJECT_MAX_LENGTH;
  protected readonly messageMaxLength = MESSAGE_MAX_LENGTH;

  protected readonly form = this.fb.group({
    subject: ['', [notJavaBlank, subjectMaxLength]],
    // No preselected category: the customer chooses it (ADR-015 §5).
    category: this.fb.control<TicketCategory | null>(null, Validators.required),
    referenceNumber: ['', referenceNumberFormat],
    message: ['', [notJavaBlank, Validators.maxLength(MESSAGE_MAX_LENGTH)]],
  });
  protected readonly message = toSignal(this.form.controls.message.valueChanges, {
    initialValue: '',
  });
  protected readonly submitting = signal(false);
  protected readonly formError = signal<string | null>(null);

  submit(): void {
    // The disabled attribute updates only on the next render, so a second submit can arrive first (ADR-014 §7).
    if (this.submitting()) {
      return;
    }
    this.formError.set(null);
    // The errors appear without markAllAsTouched(): the submit already set
    // FormGroupDirective.submitted, which Material's error rule reads (ADR-015 §5).
    if (this.form.invalid) {
      return;
    }
    // Controls stay enabled: enable() after the response would wipe the server errors.
    const { category, ...values } = this.form.getRawValue();
    this.submitting.set(true);
    // A valid form has a category (Validators.required). Values are sent as typed.
    this.api
      .create({ ...values, category: category! })
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        // Back returns to the list, not to the form (ADR-015 §1).
        next: (ticket) =>
          void this.router.navigateByUrl(`/client/tickets/${ticket.id}`, { replaceUrl: true }),
        error: (error: HttpErrorResponse) => this.showError(error),
      });
  }

  private showError(error: HttpErrorResponse): void {
    // A 401 ends the session through the interceptor; the page shows nothing (ADR-014).
    if (error.status === 401) {
      return;
    }
    const problem = toProblem(error);
    // Grouped by field, not by position: the order of errors[] is not guaranteed.
    const messages = new Map<Field, string[]>();
    let unknownField = false;
    for (const { field, message } of problem.errors ?? []) {
      if (this.isField(field)) {
        messages.set(field, [...(messages.get(field) ?? []), message]);
      } else {
        unknownField = true;
      }
    }
    // The next value change runs the validators again and replaces these errors.
    for (const [field, fieldMessages] of messages) {
      this.form.controls[field].setErrors({ server: fieldMessages.join('; ') });
    }
    if (unknownField || messages.size === 0) {
      this.formError.set(problem.detail ?? GENERIC_ERROR_MESSAGE);
    }
  }

  // Own control names only: 'constructor' in form.controls would be true (ADR-015 §5).
  private isField(field: string): field is Field {
    return Object.hasOwn(this.form.controls, field);
  }
}
