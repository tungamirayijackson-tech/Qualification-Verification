import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Institution, OnboardInstitutionRequest, SigningKey } from './institution';

/**
 * Reads the institution register.
 *
 * Paths are relative, so the same build works behind the dev proxy and in production where the
 * API serves the console's static files from the same origin. No base-URL environment file to
 * get wrong at deploy time.
 */
@Injectable({ providedIn: 'root' })
export class InstitutionService {
  private readonly http = inject(HttpClient);

  list(eligibleOnly = false): Observable<Institution[]> {
    const params = new HttpParams().set('eligibleOnly', eligibleOnly);
    return this.http.get<Institution[]>('/api/v1/institutions', { params });
  }

  /** Admits an awarding body. ADMIN only; the API refuses anyone else. */
  onboard(request: OnboardInstitutionRequest): Observable<Institution> {
    return this.http.post<Institution>('/api/v1/institutions', request);
  }

  /**
   * Issues the key an institution signs with, replacing the current one if it has one.
   *
   * `from` is optional and means today. It exists for the one case that is not now: an
   * institution whose credentials predate the register, where the key has to be valid from the
   * earliest award date or none of those credentials would verify.
   */
  issueKey(institutionId: string, from?: string): Observable<SigningKey> {
    return this.http.post<SigningKey>(
      `/api/v1/institutions/${encodeURIComponent(institutionId)}/keys`,
      from ? { from } : {}
    );
  }
}
