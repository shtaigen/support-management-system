import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButton } from '@angular/material/button';
import { MatCard, MatCardContent, MatCardHeader, MatCardTitle } from '@angular/material/card';
import { MatError, MatFormField, MatLabel } from '@angular/material/form-field';
import { MatInput } from '@angular/material/input';
import { MatProgressSpinner } from '@angular/material/progress-spinner';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { ROLE_HOME } from '../../core/auth/user-role';
import { GENERIC_ERROR_MESSAGE, toProblem } from '../../core/http/problem-detail';

// FR-01: at most 320 characters (docs/requirements.md).
const EMAIL_MAX_LENGTH = 320;

@Component({
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    MatButton,
    MatCard,
    MatCardContent,
    MatCardHeader,
    MatCardTitle,
    MatError,
    MatFormField,
    MatInput,
    MatLabel,
    MatProgressSpinner,
  ],
  selector: 'app-login',
  template: `
    <mat-card appearance="outlined">
      <mat-card-header>
        <mat-card-title>Sign in</mat-card-title>
      </mat-card-header>
      <mat-card-content>
        @if (expiredNotice()) {
          <p class="message" role="alert">Your session has expired. Please sign in again.</p>
        }
        @if (error(); as message) {
          <p class="message error" role="alert">{{ message }}</p>
        }
        <form [formGroup]="form" (ngSubmit)="submit()">
          <mat-form-field>
            <mat-label>Email</mat-label>
            <input matInput formControlName="email" type="email" autocomplete="username" />
            @if (form.controls.email.hasError('required')) {
              <mat-error>Email is required.</mat-error>
            } @else if (form.controls.email.hasError('maxlength')) {
              <mat-error>Email must be at most {{ emailMaxLength }} characters.</mat-error>
            } @else if (form.controls.email.hasError('email')) {
              <mat-error>Enter a valid email address.</mat-error>
            }
          </mat-form-field>
          <mat-form-field>
            <mat-label>Password</mat-label>
            <input
              matInput
              formControlName="password"
              type="password"
              autocomplete="current-password"
            />
            @if (form.controls.password.hasError('required')) {
              <mat-error>Password is required.</mat-error>
            }
          </mat-form-field>
          <button mat-flat-button type="submit" [disabled]="submitting()">
            @if (submitting()) {
              <mat-progress-spinner mode="indeterminate" diameter="20" />
            }
            Sign in
          </button>
        </form>
      </mat-card-content>
    </mat-card>
  `,
  styles: `
    :host {
      display: flex;
      justify-content: center;
      padding: 24px;
    }
    mat-card {
      width: 100%;
      max-width: 400px;
    }
    form {
      display: flex;
      flex-direction: column;
      gap: 8px;
      margin-top: 16px;
    }
    .message {
      margin: 16px 0 0;
    }
    .error {
      color: var(--mat-sys-error);
    }
    mat-progress-spinner {
      display: inline-block;
      margin-right: 8px;
      vertical-align: middle;
    }
  `,
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly emailMaxLength = EMAIL_MAX_LENGTH;

  protected readonly form = inject(NonNullableFormBuilder).group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(EMAIL_MAX_LENGTH)]],
    password: ['', Validators.required],
  });

  protected readonly submitting = signal(false);
  protected readonly expiredNotice = signal(
    inject(ActivatedRoute).snapshot.queryParamMap.get('expired') === '1',
  );
  protected readonly error = signal<string | null>(null);

  submit(): void {
    // The disabled attribute updates only on the next render, so a second submit can arrive first (ADR-014 §7).
    if (this.submitting()) {
      return;
    }
    this.expiredNotice.set(false);
    this.error.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    const { email, password } = this.form.getRawValue();
    this.auth.login(email, password).subscribe({
      next: (session) => {
        this.submitting.set(false);
        void this.router.navigateByUrl(ROLE_HOME[session.user.role], { replaceUrl: true });
      },
      error: (error: HttpErrorResponse) => {
        this.submitting.set(false);
        this.error.set(toProblem(error).detail ?? GENERIC_ERROR_MESSAGE);
      },
    });
  }
}
