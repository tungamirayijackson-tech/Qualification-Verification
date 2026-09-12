import { HttpErrorResponse } from '@angular/common/http';

import { describeFailure, problemType } from './problem';

/**
 * The console's failure messages are the only explanation most users ever get, so the mapping
 * from an RFC 9457 body to a sentence is worth testing rather than assuming.
 */
describe('describeFailure', () => {
  function problem(status: number, body: unknown): HttpErrorResponse {
    return new HttpErrorResponse({ status, error: body, statusText: 'error' });
  }

  it('prefers field-level validation messages, which say which box to fix', () => {
    const failure = problem(400, {
      type: 'https://qvs.ac.zw/problems/validation-failed',
      errors: [
        { field: 'holderNationalId', message: 'a national ID is 13 digits' },
        { field: 'awardedOn', message: 'the award date is required' }
      ]
    });

    expect(describeFailure(failure)).toBe(
      'holderNationalId: a national ID is 13 digits; ' + 'awardedOn: the award date is required'
    );
  });

  it('uses the server detail, which is written for the reader', () => {
    const failure = problem(422, {
      type: 'https://qvs.ac.zw/problems/institution-not-accredited',
      detail: 'Lapsed College was not accredited on 2026-04-11 (accreditation ended 2024-01-31)',
      reason: 'INSTITUTION_NOT_ACCREDITED'
    });

    expect(describeFailure(failure)).toContain('was not accredited on 2026-04-11');
  });

  it('falls back to a status-appropriate sentence when the body says nothing useful', () => {
    expect(describeFailure(problem(403, {}))).toBe('Your role does not permit that action.');
    expect(describeFailure(problem(409, {}))).toBe(
      'That record is already in the state you asked for.'
    );
    expect(describeFailure(problem(429, {}))).toContain('Too many requests');
  });

  it('distinguishes an unreachable server from a rejected request', () => {
    // Status 0 is what a browser reports when the request never arrived. Telling the user to
    // check their connection is actionable; "something went wrong" is not.
    expect(describeFailure(problem(0, null))).toContain('Could not reach the server');
  });

  it('handles a failure that is not an HTTP error at all', () => {
    expect(describeFailure(new Error('boom'))).toBe('Something went wrong. Please try again.');
    expect(describeFailure(undefined)).toBe('Something went wrong. Please try again.');
  });
});

describe('problemType', () => {
  it('exposes the stable type URI so the console never matches on prose', () => {
    const failure = new HttpErrorResponse({
      status: 422,
      error: { type: 'https://qvs.ac.zw/problems/qualification-phased-out' }
    });

    expect(problemType(failure)).toBe('https://qvs.ac.zw/problems/qualification-phased-out');
  });

  it('returns null when there is no problem body', () => {
    expect(problemType(new Error('boom'))).toBeNull();
    expect(problemType(new HttpErrorResponse({ status: 500, error: {} }))).toBeNull();
  });
});
