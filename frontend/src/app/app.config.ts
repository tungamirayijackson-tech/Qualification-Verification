import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { authInterceptor } from './core/auth/auth.interceptor';
import { routes } from './app.routes';

/**
 * Application wiring.
 *
 * The HTTP client is registered once, centrally, with the auth interceptor attached. That is
 * what makes the interceptor unavoidable: there is no second client a screen could inject to
 * bypass it, so no request can accidentally go out without a token or, just as importantly,
 * accidentally go out to the public path *with* one.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor]))
  ]
};
