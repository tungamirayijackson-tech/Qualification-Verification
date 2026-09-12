import { CommonModule } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { describeFailure } from '../../core/api/problem';
import { CHECK_LABELS, CheckName, VerificationResult } from '../../core/api/verification';
import { VerificationService } from '../../core/api/verification.service';
import { IconComponent, IconName } from '../../shared/icon.component';

type State = 'idle' | 'checking' | 'answered' | 'failed';

/**
 * What a share token can look like.
 *
 * Base64url and nothing else — see `ShareToken.mint`. This is the character set rather than the
 * length: the length is an implementation detail that may change, while "a token is URL-safe"
 * is the property every share link rests on.
 */
const TOKEN_SHAPE = /^[A-Za-z0-9_-]+$/;

/**
 * The public credential check (FR-06).
 *
 * This is the only screen a stranger sees, and everything on it is shaped by that.
 *
 * It shows **all four checks**, not just the verdict. A verifier told only "valid" is being
 * asked to trust the system; a verifier shown that the signature verified against a published
 * key, that the issuer had standing on the award date, that the credential is not revoked and
 * that the issuance is in the audit chain is being shown the reasoning. The revoked case is
 * where this earns its place: the signature reads PASS while revocation reads FAIL, and that
 * apparent contradiction is the honest answer — the award was made, and later withdrawn.
 *
 * On the not-found path it shows the verdict and nothing else, because the API returns nothing
 * else. Unknown, expired, withdrawn and tampered all produce the identical response, so this
 * page cannot be used to work out which.
 */
@Component({
  selector: 'app-public-verify',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './public-verify.component.html',
  styleUrl: './public-verify.component.scss'
})
export class PublicVerifyComponent {
  private readonly verification = inject(VerificationService);

  /** Bound from `/verify/:token` when the holder shared a full link. */
  readonly token = input<string>('');

  protected readonly state = signal<State>('idle');
  protected readonly result = signal<VerificationResult | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly checkLabels = CHECK_LABELS;

  /**
   * The token the answer on screen belongs to.
   *
   * Held separately from the text in the box, which the user may have carried on editing. A
   * report link built from the box could quietly point at a different credential from the one
   * whose verdict is being read.
   */
  protected readonly answeredToken = signal<string | null>(null);

  protected enteredToken = '';

  constructor() {
    queueMicrotask(() => {
      const fromRoute = this.token();
      if (fromRoute) {
        this.enteredToken = fromRoute;
        this.check();
      }
    });
  }

  protected check(): void {
    const token = this.enteredToken.trim();
    if (!token) {
      return;
    }

    if (!TOKEN_SHAPE.test(token)) {
      // Answered here rather than asked of the server, because the answer is already known: a
      // token is base64url, so anything holding a semicolon, a slash or a space is not one this
      // system could have issued. It is also the friendlier answer. A path with a semicolon in
      // it stops matching the public route on the way in and is refused as unauthenticated —
      // correct, in that the system fails closed rather than open, but "401" is a baffling
      // thing to show somebody who has pasted one character too many.
      this.error.set(
        'That is not a certificate id this system could have issued. They are made only of ' +
          'letters, digits, hyphens and underscores — check for a stray character from the paste.'
      );
      this.state.set('failed');
      return;
    }

    this.state.set('checking');
    this.error.set(null);

    this.verification.verify(token).subscribe({
      next: (result) => {
        this.result.set(result);
        this.answeredToken.set(token);
        this.state.set('answered');
      },
      error: (failure: unknown) => {
        this.error.set(describeFailure(failure));
        this.state.set('failed');
      }
    });
  }

  /** The download link for a report of the check on screen. */
  protected reportUrl(): string {
    const token = this.answeredToken();
    return token ? this.verification.reportUrl(token) : '';
  }

  protected outcomeOf(result: VerificationResult, check: CheckName): string {
    return result.checks?.[check] ?? 'NOT_RUN';
  }

  /**
   * The emblem for the headline verdict.
   *
   * <p>It restates what the pill and the sentence beneath it already say. That repetition is
   * the point: this is the one screen a stranger uses once, under time pressure, often on a
   * phone, and the shape of a tick or a cross is legible before any of the words are. It is
   * never the only carrier of the answer — the verdict is written in text beside it, and the
   * icon is hidden from assistive technology — because an interface that says "withdrawn"
   * only in red and only as a symbol has told a screen-reader user nothing.
   */
  protected verdictIcon(verdict: string): IconName {
    switch (verdict) {
      case 'VALID':
        return 'checkCircle';
      case 'REVOKED':
        return 'xCircle';
      default:
        return 'helpCircle';
    }
  }

  /**
   * The headline above the sentence: what happened, in three or four words.
   *
   * <p>Separate from {@link #verdictWording} because a banner needs both a label somebody
   * reads at a glance and a sentence somebody reads when the glance was not enough. The raw
   * verdict is also shown, as a pill, because VALID and REVOKED are the words that appear in
   * the signed report and in the audit ledger — a verifier quoting this check should be
   * quoting the same word the system does.
   */
  protected verdictHeadline(verdict: string): string {
    switch (verdict) {
      case 'VALID':
        return 'Qualification verified';
      case 'REVOKED':
        return 'Qualification withdrawn';
      default:
        return 'No credential found';
    }
  }

  /** Clears the answer so the box is ready for the next token. */
  protected startOver(): void {
    this.result.set(null);
    this.answeredToken.set(null);
    this.error.set(null);
    this.enteredToken = '';
    this.state.set('idle');
  }

  /** The pill class for the headline verdict. */
  protected verdictClass(verdict: string): string {
    switch (verdict) {
      case 'VALID':
        return 'ok';
      case 'REVOKED':
        return 'bad';
      default:
        return 'warn';
    }
  }

  protected verdictWording(verdict: string): string {
    switch (verdict) {
      case 'VALID':
        return 'This qualification is genuine and currently stands.';
      case 'REVOKED':
        return 'This qualification was awarded and has since been withdrawn.';
      default:
        return 'No valid credential stands behind this link.';
    }
  }
}
