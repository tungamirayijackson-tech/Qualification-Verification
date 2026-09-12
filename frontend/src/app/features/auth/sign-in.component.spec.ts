import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { SignInComponent } from './sign-in.component';

/**
 * The enrolment step, which is the one screen a user cannot get past by guessing.
 *
 * The URI assertions matter more than they look. An `otpauth://` URI with the wrong parameter
 * names still scans cleanly — the authenticator simply falls back to its own defaults — so a
 * mistake here produces an app that shows six-digit codes the server rejects, with nothing on
 * either side saying why.
 */
describe('SignInComponent enrolment', () => {
  let fixture: ComponentFixture<SignInComponent>;
  let component: SignInComponent;

  /** The component's members are `protected`, which TypeScript enforces only at compile time. */
  interface Internals {
    email: string;
    mfaSecret: { set(value: string | null): void };
    qrDataUrl: { (): string | null; set(value: string | null): void };
    otpauthUri(): string;
    step: { set(value: 'credentials' | 'enrol-mfa'): void };
  }

  function internals(): Internals {
    return component as unknown as Internals;
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SignInComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()]
    }).compileComponents();

    fixture = TestBed.createComponent(SignInComponent);
    component = fixture.componentInstance;
  });

  it('builds an otpauth URI an authenticator will read correctly', () => {
    internals().email = 'registrar@example.ac.zw';
    internals().mfaSecret.set('JBSWY3DPEHPK3PXP');

    const uri = internals().otpauthUri();

    expect(uri.startsWith('otpauth://totp/')).toBeTrue();
    expect(uri).toContain('secret=JBSWY3DPEHPK3PXP');
    expect(uri).toContain('issuer=QVS');
    // Both must match the server: Totp.DIGITS is 6 and Totp.STEP_SECONDS is 30. An
    // authenticator told otherwise produces codes that will never verify.
    expect(uri).toContain('digits=6');
    expect(uri).toContain('period=30');
  });

  it('escapes the label, so an address cannot break the URI', () => {
    internals().email = 'a b@example.ac.zw';
    internals().mfaSecret.set('JBSWY3DPEHPK3PXP');

    const label = internals().otpauthUri().split('?')[0].replace('otpauth://totp/', '');

    expect(label).not.toContain(' ');
    expect(decodeURIComponent(label)).toBe('QVS:a b@example.ac.zw');
  });

  it('produces no URI before a secret has been issued', () => {
    expect(internals().otpauthUri()).toBe('');
  });

  it('renders the setup key as a scannable QR image', async () => {
    internals().email = 'registrar@example.ac.zw';
    internals().mfaSecret.set('JBSWY3DPEHPK3PXP');
    internals().step.set('enrol-mfa');

    // renderQrCode is private and driven by the sign-in response; call it the same way the
    // component does, then wait for the library's promise to settle.
    (component as unknown as { renderQrCode(): void }).renderQrCode();
    await fixture.whenStable();

    expect(internals().qrDataUrl()).toMatch(/^data:image\/png;base64,/);
  });

  it('keeps the manual setup key on screen alongside the QR', async () => {
    // The QR is unusable to anyone reading with a screen reader, and to anyone whose
    // authenticator lives on the same device as the browser. The key must never be the thing
    // that gets hidden behind it.
    internals().email = 'registrar@example.ac.zw';
    internals().mfaSecret.set('JBSWY3DPEHPK3PXP');
    internals().step.set('enrol-mfa');
    (component as unknown as { renderQrCode(): void }).renderQrCode();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('JBSWY3DPEHPK3PXP');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('img.ng-star-inserted, .qr img')
    ).not.toBeNull();
  });
});
