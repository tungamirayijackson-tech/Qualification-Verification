import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { catchError, map, of } from 'rxjs';

import { Role } from './session.model';
import { SessionService } from './session.service';

/**
 * Requires a signed-in user, recovering a session from a page reload if one is available.
 *
 * The recovery step is what makes a browser refresh survivable: the access token lives only in
 * memory, so after a reload the console has a refresh token and nothing else, and has to spend
 * it before it knows who the user is.
 */
export const signedInGuard: CanActivateFn = (_route, state) => {
  const session = inject(SessionService);
  const router = inject(Router);

  if (session.isSignedIn()) {
    return true;
  }

  if (!session.hasStoredSession()) {
    return router.createUrlTree(['/sign-in'], {
      queryParams: { next: state.url }
    });
  }

  return session.refresh().pipe(
    map(() => true as const),
    catchError(() =>
      of(
        router.createUrlTree(['/sign-in'], {
          queryParams: { next: state.url }
        })
      )
    )
  );
};

/**
 * Requires one of the given roles.
 *
 * **This is a convenience, not a security boundary.** It stops a registrar from navigating to a
 * screen whose every request would be refused anyway, which is a kindness rather than a
 * control. The actual authorisation lives in the API's filter chain and its `@PreAuthorize`
 * annotations, because an attacker does not run the console's guards — they call the endpoint.
 */
export function roleGuard(...allowed: readonly Role[]): CanActivateFn {
  return (route, state) => {
    const session = inject(SessionService);
    const router = inject(Router);

    const proceed = signedInGuard(route, state);

    if (proceed !== true) {
      return proceed;
    }
    return session.hasRole(...allowed) ? true : router.createUrlTree(['/forbidden']);
  };
}
