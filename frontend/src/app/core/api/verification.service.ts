import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { VerificationResult } from './verification';

/**
 * The public credential check.
 *
 * The only method here takes a share token, and there is deliberately no method that looks a
 * credential up by serial, by holder or by anything else. Decision 09-A: a verifier can only
 * check a credential whose token the holder gave them, and a service with no other method is
 * one nobody can accidentally widen.
 */
@Injectable({ providedIn: 'root' })
export class VerificationService {
  private readonly http = inject(HttpClient);

  verify(shareToken: string): Observable<VerificationResult> {
    return this.http.get<VerificationResult>(`/public/v1/verify/${encodeURIComponent(shareToken)}`);
  }

  /**
   * Where the signed PDF report for a token lives (FR-12).
   *
   * A URL rather than a request, because the browser downloads this one itself: the server
   * sends `Content-Disposition: attachment`, and letting a plain link do the work means no
   * blob in memory, no object URL to revoke, and a download that behaves the way the user's
   * browser is configured to behave rather than the way this code assumes it should.
   */
  reportUrl(shareToken: string): string {
    return `/public/v1/verify/${encodeURIComponent(shareToken)}/report`;
  }
}
