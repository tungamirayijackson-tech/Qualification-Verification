import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { PublicVerifyComponent } from './public-verify.component';

/**
 * The public door, and what it does with something that is not a token.
 *
 * A share token is base64url, so a string carrying a semicolon or a space is not one this
 * system could have issued. Sending it anyway produced a 401 — the path stops matching the
 * public route on the way in, so the request falls through to "must be authenticated". That is
 * the system failing closed, which is the right direction, but "401" is a baffling thing to
 * show somebody who has pasted one character too many.
 */
describe('PublicVerifyComponent', () => {
  let fixture: ComponentFixture<PublicVerifyComponent>;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PublicVerifyComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(PublicVerifyComponent);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());

  function checkToken(token: string): void {
    const box = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input')!;
    box.value = token;
    box.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement)
      .querySelector<HTMLFormElement>('form')!
      .dispatchEvent(new Event('submit'));
    fixture.detectChanges();
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('says so, without asking the server, when the token could not have been issued', () => {
    checkToken('lnlknvlssdnbsnbls;lbn;lds;ldslnb;lns');

    // http.verify() in afterEach is the assertion that nothing was sent.
    expect(text()).toContain('not a certificate id this system could have issued');
  });

  it('rejects a token with a space in it for the same reason', () => {
    checkToken('two words');

    expect(text()).toContain('not a certificate id this system could have issued');
  });

  it('does ask the server about anything shaped like a token', () => {
    // Including one that will not be found: whether it exists is the server's answer to give,
    // and the check is recorded in the ledger, so it must not be short-circuited here.
    checkToken('oK46PKr4GS_bW0rzxglLog');

    const asked = http.expectOne((candidate) =>
      candidate.url.includes('/public/v1/verify/oK46PKr4GS_bW0rzxglLog')
    );
    asked.flush({ verdict: 'NOT_FOUND', verifiedAt: '2026-09-08T12:00:00Z' });
    fixture.detectChanges();

    expect(text()).not.toContain('not a certificate id this system could have issued');
  });
});
