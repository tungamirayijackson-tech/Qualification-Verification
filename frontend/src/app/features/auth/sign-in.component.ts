import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { toDataURL } from 'qrcode';

import { describeFailure } from '../../core/api/problem';
import { SessionService } from '../../core/auth/session.service';
import { IconComponent } from '../../shared/icon.component';

/** Which step of signing in the form is showing. */
type Step = 'credentials' | 'enrol-mfa';

/**
 * Signing in, including first-time enrolment of a second factor.
 *
 * The enrolment step lives on this screen rather than a separate route because it is not a
 * separate decision for the user: they tried to sign in, and the system told them there is one
 * more thing to do first. Sending them somewhere else and back would make a setup step feel
 * like a failure.
 *
 * The console never explains why a sign-in failed beyond what the API says, and the API says
 * the same thing for a wrong password, an unknown address and a disabled account. That is
 * deliberate: a login form that tells those apart is a directory of who holds an account.
 */
@Component({
  selector: 'app-sign-in',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './sign-in.component.html',
  styleUrl: './sign-in.component.scss'
})
export class SignInComponent {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly step = signal<Step>('credentials');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly note = signal<string | null>(null);

  protected email = '';
  protected password = '';
  protected totpCode = '';

  /** The shared secret handed out at enrolment. Shown once; the console never stores it. */
  protected readonly mfaSecret = signal<string | null>(null);

  /**
   * The enrolment QR, as a data URL.
   *
   * Rendered in the browser rather than fetched from the server. The secret is already in the
   * response the browser just received, so drawing it locally adds no exposure — whereas asking
   * a server for "the QR image for this secret" would put the secret in a URL, and URLs end up
   * in proxy logs and browser history.
   */
  protected readonly qrDataUrl = signal<string | null>(null);

  constructor() {
    // A session that ended mid-use should say so, rather than presenting an empty form as
    // though the user had simply arrived.
    if (this.route.snapshot.queryParamMap.get('reason') === 'expired') {
      this.error.set('Your session ended. Please sign in again.');
    }
  }

  protected submit(): void {
    this.busy.set(true);
    this.error.set(null);
    this.note.set(null);

    this.session.signIn(this.email, this.password, this.totpCode || undefined).subscribe({
      next: (outcome) => {
        this.busy.set(false);
        if (outcome.kind === 'signed-in') {
          void this.router.navigateByUrl(this.destination());
        } else {
          this.mfaSecret.set(outcome.secret);
          this.step.set('enrol-mfa');
          this.renderQrCode();
        }
      },
      error: (failure: unknown) => {
        this.busy.set(false);
        this.error.set(describeFailure(failure));
      }
    });
  }

  protected confirmEnrolment(): void {
    this.busy.set(true);
    this.error.set(null);

    this.session.completeEnrolment(this.email, this.totpCode).subscribe({
      next: () => {
        // Enrolment does not sign the user in; it makes signing in possible. Clearing the code
        // matters: the one they just used belongs to this time step and would be refused as a
        // login code moments later.
        this.busy.set(false);
        this.totpCode = '';
        this.step.set('credentials');
        this.mfaSecret.set(null);
        this.note.set('Second factor enrolled. Sign in with a fresh code.');
      },
      error: () => {
        this.busy.set(false);
        this.error.set(
          'That code was not accepted. Check your authenticator and try the next one.'
        );
      }
    });
  }

  /**
   * Draws the otpauth URI as a scannable QR code.
   *
   * Fixed black-on-white regardless of the page theme. A QR rendered in theme colours looks
   * tidier and scans badly or not at all — the contrast between modules is what the camera
   * reads, so this is one of the few places where ignoring the colour scheme is correct.
   *
   * If it fails, the manual setup key stays on screen and enrolment still works. A missing
   * picture should never be the thing that locks somebody out of their own account.
   */
  private renderQrCode(): void {
    this.qrDataUrl.set(null);

    void toDataURL(this.otpauthUri(), {
      errorCorrectionLevel: 'M',
      margin: 2,
      width: 208,
      color: { dark: '#000000ff', light: '#ffffffff' }
    })
      .then((dataUrl) => this.qrDataUrl.set(dataUrl))
      .catch(() => this.qrDataUrl.set(null));
  }

  /** The otpauth URI an authenticator app can take as a manual entry. */
  protected otpauthUri(): string {
    const secret = this.mfaSecret();
    if (!secret) {
      return '';
    }
    const label = encodeURIComponent('QVS:' + this.email);
    return 'otpauth://totp/' + label + '?secret=' + secret + '&issuer=QVS&digits=6&period=30';
  }

  private destination(): string {
    return this.route.snapshot.queryParamMap.get('next') ?? '/credentials';
  }
}
