import { CommonModule } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import {
  CredentialDetail,
  REVOCATION_REASONS,
  RevocationReason,
  ShareTokenIssued
} from '../../core/api/credential';
import { CredentialService } from '../../core/api/credential.service';
import { describeFailure } from '../../core/api/problem';
import { SessionService } from '../../core/auth/session.service';
import { IconComponent } from '../../shared/icon.component';

type LoadState = 'loading' | 'ready' | 'failed';

/**
 * One credential in full (FR-04), with the two actions a registrar can take on it.
 *
 * The screen shows the detached signature and the exact bytes it covers. That looks like
 * clutter until you consider who reads this page: an auditor settling a dispute needs to check
 * the signature with their own tooling rather than trusting this system's opinion of it. A
 * register that will only report its own verdict is asking to be believed; one that publishes
 * the signature and the signed bytes is offering to be checked.
 */
@Component({
  selector: 'app-credential-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './credential-detail.component.html',
  styleUrl: './credential-detail.component.scss'
})
export class CredentialDetailComponent {
  private readonly credentials = inject(CredentialService);
  private readonly session = inject(SessionService);

  /** Bound from the route by `withComponentInputBinding()`. */
  readonly serial = input.required<string>();

  protected readonly state = signal<LoadState>('loading');
  protected readonly credential = signal<CredentialDetail | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);

  protected readonly reasons = REVOCATION_REASONS;
  protected readonly canWrite = this.session.hasRole('REGISTRAR');

  /** The freshly minted token, shown once and never retrievable again. */
  protected readonly shareToken = signal<ShareTokenIssued | null>(null);
  protected readonly showRevoke = signal(false);

  protected shareTtlDays = 30;
  protected shareLabel = '';
  protected revokeReason: RevocationReason = 'ADMINISTRATIVE_ERROR';
  protected revokeNote = '';

  constructor() {
    // input.required is resolved by the time the constructor's effect would run, but a plain
    // read here is simpler and this screen is only ever entered with a serial in the URL.
    queueMicrotask(() => this.load());
  }

  protected load(): void {
    this.state.set('loading');
    this.credentials.detail(this.serial()).subscribe({
      next: (detail) => {
        this.credential.set(detail);
        this.state.set('ready');
      },
      error: (failure: unknown) => {
        this.error.set(describeFailure(failure));
        this.state.set('failed');
      }
    });
  }

  protected mintShareToken(): void {
    this.busy.set(true);
    this.error.set(null);

    this.credentials
      .mintShareToken(this.serial(), this.shareTtlDays, this.shareLabel.trim() || null)
      .subscribe({
        next: (issued) => {
          this.busy.set(false);
          this.shareToken.set(issued);
          this.shareLabel = '';
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          this.error.set(describeFailure(failure));
        }
      });
  }

  protected revoke(): void {
    this.busy.set(true);
    this.error.set(null);

    this.credentials
      .revoke(this.serial(), this.revokeReason, this.revokeNote.trim() || undefined)
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.showRevoke.set(false);
          this.revokeNote = '';
          // Reload rather than patching the local copy: the authoritative revocation time and
          // actor come from the server, and showing a guess at them on an audit screen would
          // be worse than showing nothing.
          this.load();
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          this.error.set(describeFailure(failure));
        }
      });
  }

  /** The absolute link a holder hands to a verifier. */
  protected shareLink(issued: ShareTokenIssued): string {
    return window.location.origin + issued.verifyUrl;
  }

  protected requiresNote(): boolean {
    return this.revokeReason === 'OTHER';
  }
}
