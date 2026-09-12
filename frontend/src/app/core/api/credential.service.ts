import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CredentialDetail,
  CredentialSummary,
  RegisterCredentialRequest,
  RevocationReason,
  ShareTokenIssued
} from './credential';

/**
 * The registrar's operations on the register.
 *
 * Paths are relative, so one build works behind the dev proxy and in production where the API
 * serves the console's static files from the same origin. There is no base-URL environment file
 * to get wrong at deploy time.
 */
@Injectable({ providedIn: 'root' })
export class CredentialService {
  private readonly http = inject(HttpClient);

  /**
   * FR-01. The institution is never sent: the server takes it from the registrar's own token,
   * so a request cannot claim to act for somewhere else.
   */
  register(request: RegisterCredentialRequest): Observable<CredentialSummary> {
    return this.http.post<CredentialSummary>('/api/v1/credentials', request);
  }

  /** FR-04. */
  detail(serial: string): Observable<CredentialDetail> {
    return this.http.get<CredentialDetail>(`/api/v1/credentials/${encodeURIComponent(serial)}`);
  }

  /** FR-07. The signature is left intact; revocation is a separate fact. */
  revoke(serial: string, reason: RevocationReason, note?: string): Observable<CredentialSummary> {
    return this.http.post<CredentialSummary>(
      `/api/v1/credentials/${encodeURIComponent(serial)}/revoke`,
      { reason, note: note ?? null }
    );
  }

  /** FR-06. The returned token is shown once and is not recoverable afterwards. */
  mintShareToken(
    serial: string,
    ttlDays: number | null,
    label: string | null
  ): Observable<ShareTokenIssued> {
    return this.http.post<ShareTokenIssued>(
      `/api/v1/credentials/${encodeURIComponent(serial)}/share`,
      { ttlDays, label }
    );
  }
}
