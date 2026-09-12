import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ImportReport } from './import';

/** FR-02: uploads a graduation cohort. */
@Injectable({ providedIn: 'root' })
export class ImportService {
  private readonly http = inject(HttpClient);

  /**
   * Uploads a CSV.
   *
   * The Content-Type header is deliberately not set. The browser has to generate it, because a
   * multipart body needs a boundary token that only the browser knows — setting it by hand
   * produces a header with no boundary and a request the server cannot parse.
   */
  upload(file: File): Observable<ImportReport> {
    const body = new FormData();
    body.append('file', file, file.name);

    return this.http.post<ImportReport>('/api/v1/credentials/import', body);
  }
}
