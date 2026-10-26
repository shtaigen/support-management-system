import { HttpErrorResponse } from '@angular/common/http';

// RFC 9457 body as returned by the backend (ADR-008).
export interface ProblemDetail {
  status: number;
  title: string;
  detail?: string;
  errors?: { field: string; message: string }[];
}

export const UNREACHABLE_MESSAGE = 'Cannot reach the server. Please try again.';
export const GENERIC_ERROR_MESSAGE = 'Something went wrong. Please try again.';

export function toProblem(error: HttpErrorResponse): ProblemDetail {
  if (isProblemDetail(error.error)) {
    return error.error;
  }
  const message = error.status === 0 ? UNREACHABLE_MESSAGE : GENERIC_ERROR_MESSAGE;
  return { status: error.status, title: message, detail: message };
}

// Accepted only as an object with a numeric status (ADR-014 §6).
function isProblemDetail(body: unknown): body is ProblemDetail {
  return (
    typeof body === 'object' && body !== null && typeof (body as ProblemDetail).status === 'number'
  );
}
