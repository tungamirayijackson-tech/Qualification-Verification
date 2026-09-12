import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { authInterceptor } from './auth.interceptor';
import { SessionService } from './session.service';

/**
 * The interceptor's job is small and two of its rules are security properties rather than
 * conveniences, so they are asserted directly.
 */
describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let session: SessionService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting()
      ]
    });

    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    session = TestBed.inject(SessionService);
  });

  afterEach(() => {
    httpMock.verify();
    session.signOut();
  });

  /** Puts a session in place without going through the network. */
  function signInAs(token: string): void {
    (session as unknown as { accessToken: { set(value: string): void } }).accessToken.set(token);
  }

  it('attaches the bearer token to console requests', () => {
    signInAs('test-access-token');

    http.get('/api/v1/credentials/ZW-PR0142-2026-000001').subscribe();

    const request = httpMock.expectOne('/api/v1/credentials/ZW-PR0142-2026-000001');
    expect(request.request.headers.get('Authorization')).toBe('Bearer test-access-token');
    request.flush({});
  });

  it('never attaches a token to the public verification path', () => {
    // This is the property that matters most. Sending a registrar's token to the public
    // endpoint would turn an anonymous check into an attributable one, changing what lands in
    // the audit trail without anybody choosing that.
    signInAs('test-access-token');

    http.get('/public/v1/verify/some-share-token').subscribe();

    const request = httpMock.expectOne('/public/v1/verify/some-share-token');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({ verdict: 'NOT_FOUND', verifiedAt: '2026-09-06T00:00:00Z' });
  });

  it('never attaches a token to the sign-in endpoints', () => {
    signInAs('test-access-token');

    http.post('/api/v1/auth/login', {}).subscribe();

    const request = httpMock.expectOne('/api/v1/auth/login');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush({});
  });

  it('sends no Authorization header when nobody is signed in', () => {
    http.get('/api/v1/institutions').subscribe();

    const request = httpMock.expectOne('/api/v1/institutions');
    expect(request.request.headers.has('Authorization')).toBeFalse();
    request.flush([]);
  });

  it('retries once with a renewed token after a 401', () => {
    signInAs('expired-token');
    spyOn(sessionStorage, 'getItem').and.returnValue('stored-refresh-token');

    let delivered: unknown = null;
    http.get('/api/v1/institutions').subscribe((body) => (delivered = body));

    httpMock
      .expectOne('/api/v1/institutions')
      .flush({}, { status: 401, statusText: 'Unauthorized' });

    httpMock.expectOne('/api/v1/auth/refresh').flush({
      accessToken: 'renewed-token',
      refreshToken: 'next-refresh-token',
      accessExpiresAt: '2026-09-06T00:15:00Z',
      tokenType: 'Bearer'
    });

    const retried = httpMock.expectOne('/api/v1/institutions');
    expect(retried.request.headers.get('Authorization')).toBe('Bearer renewed-token');
    retried.flush([{ id: 'x' }]);

    expect(delivered).toEqual([{ id: 'x' }] as unknown);
  });

  it('does not retry a 401 from the public path', () => {
    // A 401 there would mean something is badly misconfigured, and refreshing would not fix
    // it. Retrying would only turn one confusing failure into two.
    signInAs('test-access-token');

    http.get('/public/v1/verify/token').subscribe({ error: () => undefined });

    httpMock
      .expectOne('/public/v1/verify/token')
      .flush({}, { status: 401, statusText: 'Unauthorized' });

    // Asserted with a matcher rather than `expectNone`, which throws on failure but is
    // invisible to Jasmine — the runner reported this spec as having no expectations at all,
    // which is exactly how a test that really asserts nothing looks. A vacuous test is worse
    // than no test, so the two should not be indistinguishable from the outside.
    expect(httpMock.match('/api/v1/auth/refresh')).toEqual([]);
  });
});
