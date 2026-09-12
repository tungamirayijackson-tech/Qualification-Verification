import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CreateUserRequest, CreatedUser, User } from './user';

/**
 * Accounts. Administrators only; the API refuses everyone else.
 *
 * The first administrator does not come from here — when the register is empty there is nobody
 * to call it. That one is created at start-up from configuration.
 */
@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);

  list(): Observable<User[]> {
    return this.http.get<User[]>('/api/v1/users');
  }

  create(request: CreateUserRequest): Observable<CreatedUser> {
    return this.http.post<CreatedUser>('/api/v1/users', request);
  }

  /**
   * Withdraws an account's access.
   *
   * A POST to an action rather than a PATCH of a flag: suspending somebody is an event the
   * ledger records, not a field being edited.
   */
  disable(id: string): Observable<User> {
    return this.http.post<User>(`/api/v1/users/${id}/disable`, {});
  }

  /** Gives a suspended account its access back. The password is untouched. */
  restore(id: string): Observable<User> {
    return this.http.post<User>(`/api/v1/users/${id}/restore`, {});
  }

  /** Issues a new password and returns it once. It is stored only as a hash. */
  resetPassword(id: string): Observable<CreatedUser> {
    return this.http.post<CreatedUser>(`/api/v1/users/${id}/reset-password`, {});
  }

  /** Clears a second factor, so a lost phone is not a permanent lockout. */
  resetMfa(id: string): Observable<User> {
    return this.http.post<User>(`/api/v1/users/${id}/reset-mfa`, {});
  }
}
