import { HttpErrorResponse } from '@angular/common/http';

/**
 * An RFC 9457 `application/problem+json` body, as the API returns it.
 *
 * The `type` URI is the field that matters. It is stable, so the console can recognise a
 * failure without matching on English prose — a habit that breaks the first time somebody
 * rewords an exception message, and that makes translation impossible.
 */
export interface Problem {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly reason?: string;
  /** Present on a 429 (FR-11): how long the caller should wait. */
  readonly retryAfterSeconds?: number;
  readonly errors?: readonly {
    readonly field: string;
    readonly message: string;
  }[];
}

/**
 * Turns any failure into one sentence a person can act on.
 *
 * The order is deliberate. Field-level validation messages come first, because they tell the
 * user exactly which box to fix. The server's `detail` comes next, since it is written for the
 * reader — "Lapsed College was not accredited on 2026-04-11 (accreditation ended 2024-01-31)"
 * is more use than any generic phrase this function could invent. Only when there is nothing
 * usable does it fall back to a status-based message.
 */
export function describeFailure(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return 'Something went wrong. Please try again.';
  }

  if (error.status === 0) {
    return 'Could not reach the server. Check your connection and try again.';
  }

  const problem = (error.error ?? {}) as Problem;

  if (problem.errors?.length) {
    return problem.errors.map((field) => `${field.field}: ${field.message}`).join('; ');
  }

  if (problem.detail) {
    return problem.detail;
  }

  if (error.status === 429) {
    // The server always sends a wait; this is the belt-and-braces path for a proxy that
    // stripped the body. A throttled verifier who is told only "too many requests" retries
    // immediately, which is the behaviour the limit exists to prevent.
    const wait = problem.retryAfterSeconds ?? Number(error.headers?.get('Retry-After') ?? 0);
    return wait > 0
      ? `Too many requests. Please wait ${wait} seconds and try again.`
      : 'Too many requests. Please wait a moment and try again.';
  }

  switch (error.status) {
    case 401:
      return 'Those credentials were not accepted.';
    case 403:
      return 'Your role does not permit that action.';
    case 404:
      return 'That record could not be found.';
    case 409:
      return 'That record is already in the state you asked for.';
    case 422:
      return 'The register refused that entry.';
    case 429:
      return 'Too many requests. Please wait a moment and try again.';
    default:
      return 'Something went wrong. Please try again.';
  }
}

/** The stable problem type of a failure, when it carries one. */
export function problemType(error: unknown): string | null {
  if (!(error instanceof HttpErrorResponse)) {
    return null;
  }
  return ((error.error ?? {}) as Problem).type ?? null;
}
