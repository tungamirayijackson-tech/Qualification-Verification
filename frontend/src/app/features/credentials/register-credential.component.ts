import { CommonModule } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { CredentialSummary } from '../../core/api/credential';
import { CredentialService } from '../../core/api/credential.service';
import { describeFailure } from '../../core/api/problem';
import { IconComponent } from '../../shared/icon.component';

/**
 * FR-01: record a qualification against an accredited institution.
 *
 * Two things about this form are worth reading as design rather than markup.
 *
 * There is **no institution field**. The server takes the institution from the registrar's own
 * access token, so the screen cannot offer a choice that the API would refuse — and a registrar
 * cannot record an award on behalf of somewhere they are not bound to, however the request is
 * constructed.
 *
 * The national ID is collected but never shown again. It is hashed the moment it reaches the
 * server and the plaintext is not stored, so once this form is submitted the value is gone. The
 * hint says so, because a user who does not know that will assume they can look it up later.
 */
@Component({
  selector: 'app-register-credential',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './register-credential.component.html',
  styleUrl: './register-credential.component.scss'
})
export class RegisterCredentialComponent {
  private readonly credentials = inject(CredentialService);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly issued = signal<CredentialSummary | null>(null);

  protected qualificationId = '';
  protected holderNationalId = '';
  protected holderName = '';
  protected holderDateOfBirth = '';
  protected awardedOn = '';

  /** Today, as an ISO date, so the award-date field cannot offer a future day. */
  protected readonly today = new Date().toISOString().slice(0, 10);

  protected submit(): void {
    this.busy.set(true);
    this.error.set(null);
    this.issued.set(null);

    this.credentials
      .register({
        qualificationId: this.qualificationId.trim(),
        holderNationalId: this.holderNationalId.trim(),
        holderName: this.holderName.trim(),
        holderDateOfBirth: this.holderDateOfBirth || null,
        awardedOn: this.awardedOn
      })
      .subscribe({
        next: (summary) => {
          this.busy.set(false);
          this.issued.set(summary);
          // Only the identifying fields are cleared. A registrar working through a graduation
          // list keeps the same qualification and award date for every row, and re-typing them
          // is how transcription errors get made.
          this.holderNationalId = '';
          this.holderName = '';
          this.holderDateOfBirth = '';
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          this.error.set(describeFailure(failure));
        }
      });
  }

  protected openIssued(): void {
    const summary = this.issued();
    if (summary) {
      void this.router.navigate(['/credentials', summary.serial]);
    }
  }
}
