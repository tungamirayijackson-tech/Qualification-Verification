import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AuditEntry, ChainVerification } from './audit';

/** The auditor's read-only view of the ledger, plus chain verification and export. */
@Injectable({ providedIn: 'root' })
export class AuditService {
  private readonly http = inject(HttpClient);

  /** FR-08. Omit the subject for the most recent entries across the whole register. */
  query(subject?: string): Observable<AuditEntry[]> {
    let params = new HttpParams();
    if (subject) {
      params = params.set('subject', subject);
    }
    return this.http.get<AuditEntry[]>('/api/v1/audit', { params });
  }

  /** FR-08. Recomputes every hash and reports the first break. */
  verifyChain(): Observable<ChainVerification> {
    return this.http.post<ChainVerification>('/api/v1/audit/verify-chain', {});
  }

  /**
   * FR-09. Returns the CSV as text so the console can offer it as a download.
   *
   * The export is itself recorded in the ledger, which is why this is a deliberate action
   * behind a button rather than something the page does on load.
   */
  exportCsv(subject?: string): Observable<string> {
    let params = new HttpParams();
    if (subject) {
      params = params.set('subject', subject);
    }
    return this.http.get('/api/v1/audit/export', {
      params,
      responseType: 'text'
    });
  }
}
