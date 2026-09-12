import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap, throwError } from 'rxjs';

import { CurrentUser, EnrolmentRequired, Role, SignInOutcome, TokenPair } from './session.model';

/** Where the refresh token survives a page reload. Deliberately session, not local. */
const REFRESH_KEY = 'qvs.refresh';

/**
 * Holds the signed-in session.
 *
 * **Where the tokens live, and why.** The access token is kept in memory only. It is the
 * credential that actually opens the API, and anything written to `localStorage` is readable by
 * any script that manages to run on the page — so a single XSS becomes a stolen session that
 * outlives the tab. In memory it dies with the page.
 *
 * The refresh token is kept in `sessionStorage`, which is a deliberate and smaller compromise:
 * it survives a reload (so refreshing the page does not sign you out) but not a new tab or a
 * browser restart, and it is useless on its own — presenting it twice revokes the whole token
 * family server-side.
 *
 * The stronger design is an httpOnly, SameSite cookie that script cannot read at all, and §09
 * names that as the target. It needs the API to set cookies rather than return the token in a
 * response body, which is a backend change; until then this is the honest position and the
 * report should say so rather than claim the cookie design is already in place.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly http = inject(HttpClient);

  /** In memory only. Never persisted. */
  private readonly accessToken = signal<string | null>(null);
  private readonly currentUser = signal<CurrentUser | null>(null);

  /** The signed-in user, or null. Read by guards, the shell and every role-aware view. */
  readonly user = this.currentUser.asReadonly();
  readonly isSignedIn = computed(() => this.currentUser() !== null);
  readonly role = computed<Role | null>(() => this.currentUser()?.role ?? null);

  /** The bearer token for the interceptor. */
  token(): string | null {
    return this.accessToken();
  }

  /** Whether the current user holds any of the given roles. */
  hasRole(...roles: readonly Role[]): boolean {
    const held = this.role();
    return held !== null && roles.includes(held);
  }

  /**
   * Signs in.
   *
   * Resolves to either a session or an instruction to enrol a second factor. A wrong password
   * and an unknown account both reject with the same 401, because the API answers them
   * identically on purpose.
   */
  signIn(email: string, password: string, totpCode?: string): Observable<SignInOutcome> {
    const body = { email, password, totpCode: totpCode ?? null };

    return this.http.post<TokenPair>('/api/v1/auth/login', body).pipe(
      map((tokens): SignInOutcome => {
        this.adopt(tokens);
        return { kind: 'signed-in', user: this.currentUser()! };
      }),
      catchError((error: unknown) => {
        // 428 arrives here rather than in the success path, because Angular treats every
        // non-2xx status as an error. It is not a failure: the password was accepted and the
        // user simply has a second factor to set up first, so it is converted back into an
        // outcome instead of being propagated as one.
        if (error instanceof HttpErrorResponse && error.status === 428) {
          const enrolment = error.error as EnrolmentRequired;
          return of<SignInOutcome>({
            kind: 'enrol-mfa',
            secret: enrolment.mfaSecret,
            email
          });
        }
        return throwError(() => error);
      })
    );
  }

  /** Finishes MFA enrolment by proving a code can be generated from the new secret. */
  completeEnrolment(email: string, totpCode: string): Observable<void> {
    return this.http.post<void>('/api/v1/auth/mfa/enrol', { email, totpCode });
  }

  /**
   * Exchanges the stored refresh token for a new pair.
   *
   * Used on start-up so a page reload does not end the session, and by the interceptor when the
   * short-lived access token expires mid-session.
   */
  refresh(): Observable<TokenPair> {
    const refreshToken = this.storedRefreshToken();
    if (!refreshToken) {
      return new Observable((subscriber) => subscriber.error(new Error('no refresh token')));
    }
    return this.http
      .post<TokenPair>('/api/v1/auth/refresh', { refreshToken })
      .pipe(tap((tokens) => this.adopt(tokens)));
  }

  /** Whether a session might be recoverable from a previous page load. */
  hasStoredSession(): boolean {
    return this.storedRefreshToken() !== null;
  }

  /** Forgets everything. */
  signOut(): void {
    this.accessToken.set(null);
    this.currentUser.set(null);
    try {
      sessionStorage.removeItem(REFRESH_KEY);
    } catch {
      // Storage can throw in a private window or when site data is blocked. Signing out must
      // still clear the in-memory session, which is the part that matters.
    }
  }

  private adopt(tokens: TokenPair): void {
    this.accessToken.set(tokens.accessToken);
    this.currentUser.set(decodeUser(tokens.accessToken));
    try {
      sessionStorage.setItem(REFRESH_KEY, tokens.refreshToken);
    } catch {
      // Without storage the session simply does not survive a reload. That is a degraded
      // experience, not a broken one, so it is not worth failing the sign-in over.
    }
  }

  private storedRefreshToken(): string | null {
    try {
      return sessionStorage.getItem(REFRESH_KEY);
    } catch {
      return null;
    }
  }
}

/**
 * Reads the claims out of an access token.
 *
 * **This does not verify the signature, and must never be treated as though it did.** The
 * server verifies every token on every request; this only saves a round trip to learn the
 * signed-in user's own name and role so the console can decide which links to draw. A forged
 * token would let someone see a menu item they cannot use, and nothing more.
 */
function decodeUser(accessToken: string): CurrentUser | null {
  const payload = accessToken.split('.')[1];
  if (!payload) {
    return null;
  }
  try {
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const claims = JSON.parse(json) as Record<string, string>;
    return {
      userId: claims['sub'],
      email: claims['email'],
      role: claims['role'] as Role,
      institutionId: claims['inst'] ?? null
    };
  } catch {
    return null;
  }
}
