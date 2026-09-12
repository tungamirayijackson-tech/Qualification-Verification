import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CredentialSearchCriteria, CredentialSearchResults } from './search';

/**
 * FR-03.
 *
 * There is deliberately no institution parameter. The server takes the scope from the caller's
 * own token, so a registrar's search is pinned to their institution and a client cannot widen
 * it — which means this service has no way to express a request the API would refuse.
 */
@Injectable({ providedIn: 'root' })
export class SearchService {
  private readonly http = inject(HttpClient);

  search(criteria: CredentialSearchCriteria): Observable<CredentialSearchResults> {
    let params = new HttpParams().set('page', criteria.page ?? 0).set('size', criteria.size ?? 20);

    // Only the criteria the user actually filled in are sent. An empty string would be a
    // filter matching nothing rather than a filter the user meant to leave off.
    if (criteria.holderName?.trim()) {
      params = params.set('holderName', criteria.holderName.trim());
    }
    if (criteria.nqfLevel) {
      params = params.set('nqfLevel', criteria.nqfLevel);
    }
    if (criteria.status) {
      params = params.set('status', criteria.status);
    }
    if (criteria.awardedFrom) {
      params = params.set('awardedFrom', criteria.awardedFrom);
    }
    if (criteria.awardedTo) {
      params = params.set('awardedTo', criteria.awardedTo);
    }

    return this.http.get<CredentialSearchResults>('/api/v1/credentials', { params });
  }

  /**
   * Finds every credential held by one person.
   *
   * A POST, because it carries a national ID. In a query string that identifier would be
   * copied into proxy logs, browser history and referrer headers — none of which this system
   * controls, and all of which outlive the request.
   */
  searchByHolder(
    holderNationalId: string,
    page = 0,
    size = 20
  ): Observable<CredentialSearchResults> {
    return this.http.post<CredentialSearchResults>('/api/v1/credentials/search-by-holder', {
      holderNationalId,
      page,
      size
    });
  }
}
