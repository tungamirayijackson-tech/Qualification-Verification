import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, switchMap, throwError } from 'rxjs';

import { SessionService } from './session.service';

/** Requests that must never carry a bearer token. */
const UNAUTHENTICATED_PATHS = ['/api/v1/auth/', '/public/'];

/**
 * Attaches the access token, and renews it once when it has expired.
 *
 * Two things here are deliberate.
 *
 * The token is **not** attached to `/public/**` or to the sign-in endpoints. The public
 * verification path is meant to be reachable by a stranger, and sending a registrar's bearer
 * token to it would quietly turn an anonymous check into an attributable one — changing what
 * lands in the audit trail without anybody choosing that.
 *
 * A 401 triggers **one** refresh attempt, and the retried request is the only retry. A loop
 * that refreshes on every 401 will, when the refresh itself starts failing, hammer the API and
 * mask the real problem behind a wall of requests.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const session = inject(SessionService);
  const router = inject(Router);

  const isPublic = UNAUTHENTICATED_PATHS.some((path) => request.url.includes(path));
  const token = session.token();

  const authorised =
    isPublic || !token
      ? request
      : request.clone({ setHeaders: { Authorization: `Bearer ${token}` } });

  return next(authorised).pipe(
    catchError((error: unknown) => {
      const expired =
        error instanceof HttpErrorResponse && error.status === 401 && !isPublic && token !== null;

      if (!expired) {
        return throwError(() => error);
      }

      return session.refresh().pipe(
        switchMap((tokens) =>
          next(
            request.clone({
              setHeaders: { Authorization: `Bearer ${tokens.accessToken}` }
            })
          )
        ),
        catchError((refreshError: unknown) => {
          // The refresh token is spent, revoked, or its family was invalidated by a replay.
          // There is nothing to recover, so end the session rather than leaving the user
          // clicking on a console whose every request will fail.
          session.signOut();
          void router.navigate(['/sign-in'], {
            queryParams: { reason: 'expired' }
          });
          return throwError(() => refreshError);
        })
      );
    })
  );
};
