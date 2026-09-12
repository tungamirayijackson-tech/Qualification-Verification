import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AddQualificationRequest, Qualification } from './qualification';

/**
 * The qualifications an institution offers.
 *
 * This service exists as much for usability as for administration: registering an award needs a
 * qualification's identity, and before this the console asked a registrar to type a UUID they
 * had no way of looking up.
 */
@Injectable({ providedIn: 'root' })
export class QualificationService {
  private readonly http = inject(HttpClient);

  /**
   * Lists qualifications.
   *
   * The institution is ignored for a registrar -- the API takes it from their token -- and is
   * required for an administrator, who is not scoped to one.
   */
  list(institutionId?: string): Observable<Qualification[]> {
    const params = institutionId ? new HttpParams().set('institutionId', institutionId) : undefined;
    return this.http.get<Qualification[]>('/api/v1/qualifications', { params });
  }

  add(request: AddQualificationRequest): Observable<Qualification> {
    return this.http.post<Qualification>('/api/v1/qualifications', request);
  }
}
