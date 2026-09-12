import { Component, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { Institution, SigningKey } from '../../core/api/institution';
import { InstitutionService } from '../../core/api/institution.service';
import { describeFailure } from '../../core/api/problem';
import { SessionService } from '../../core/auth/session.service';
import { matchesFilter } from '../../shared/fold-text';
import { IconComponent } from '../../shared/icon.component';

type LoadState = 'loading' | 'ready' | 'failed';

/**
 * The register of awarding bodies, and the two administrative acts that shape it.
 *
 * Signals rather than an async pipe over a raw observable, because the screen has three
 * distinguishable states and a template that pretends "empty" and "still loading" are the same
 * thing is the usual way a list screen misleads its user.
 *
 * The administrative controls are hidden from anyone who is not an administrator. That is
 * presentation, not protection — the API refuses the request regardless — but a form that
 * submits into a 403 teaches people to distrust the interface.
 */
@Component({
  selector: 'app-institution-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './institution-list.component.html',
  styleUrl: './institution-list.component.scss'
})
export class InstitutionListComponent {
  private readonly service = inject(InstitutionService);
  private readonly session = inject(SessionService);

  private readonly allInstitutions = signal<Institution[]>([]);
  protected readonly state = signal<LoadState>('loading');
  protected readonly eligibleOnly = signal(false);

  /**
   * What the user has typed into the filter box.
   *
   * Filtered here rather than at the server: the whole register of awarding bodies is already
   * on the page, it is a list of tens rather than thousands, and a round trip per keystroke to
   * re-fetch rows the browser is holding would be slower and no more correct.
   */
  protected readonly filterTerm = signal('');

  /** The rows to draw: everything loaded, narrowed by the filter, ignoring case and accents. */
  protected readonly institutions = computed(() => {
    const term = this.filterTerm();
    return this.allInstitutions().filter((institution) =>
      matchesFilter(
        term,
        institution.name,
        institution.country,
        institution.providerNumber,
        institution.activeKeyId
      )
    );
  });

  protected readonly isAdmin = this.session.hasRole('ADMIN');

  /** The add-an-institution form, collapsed until asked for. */
  protected readonly adding = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly note = signal<string | null>(null);

  protected name = '';
  protected country = 'ZW';
  protected providerNumber = '';
  protected accreditedUntil = '';

  /** Which institution's key is being issued, so only that row shows a spinner. */
  protected readonly keying = signal<string | null>(null);

  constructor() {
    this.load();
  }

  /** Called on every keystroke in the filter box. */
  protected onFilter(term: string): void {
    this.filterTerm.set(term);
  }

  /** Whether the filter is hiding rows, so the screen can say so rather than look empty. */
  protected readonly isFiltered = computed(() => this.filterTerm().trim().length > 0);

  protected readonly totalLoaded = computed(() => this.allInstitutions().length);

  protected toggleEligibleOnly(): void {
    this.eligibleOnly.update((current) => !current);
    this.load();
  }

  protected load(): void {
    this.state.set('loading');
    this.service.list(this.eligibleOnly()).subscribe({
      next: (found) => {
        this.allInstitutions.set(found);
        this.state.set('ready');
      },
      error: () => this.state.set('failed')
    });
  }

  protected toggleAdding(): void {
    this.adding.update((open) => !open);
    this.error.set(null);
    this.note.set(null);
  }

  protected onboard(): void {
    this.busy.set(true);
    this.error.set(null);
    this.note.set(null);

    this.service
      .onboard({
        name: this.name.trim(),
        country: this.country.trim().toUpperCase(),
        providerNumber: this.providerNumber.trim(),
        accreditedUntil: this.accreditedUntil
      })
      .subscribe({
        next: (institution) => {
          this.busy.set(false);
          this.adding.set(false);
          // Said out loud, because the next step is not obvious and forgetting it leaves an
          // institution that looks admitted and cannot issue anything.
          this.note.set(
            `${institution.name} is in the register. It has no signing key yet, so it cannot ` +
              `issue credentials until you issue one below.`
          );
          this.name = '';
          this.providerNumber = '';
          this.accreditedUntil = '';
          this.load();
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          this.error.set(describeFailure(failure));
        }
      });
  }

  protected issueKey(institution: Institution): void {
    this.keying.set(institution.id);
    this.error.set(null);
    this.note.set(null);

    this.service.issueKey(institution.id).subscribe({
      next: (key: SigningKey) => {
        this.keying.set(null);
        this.note.set(
          key.supersedes
            ? `${institution.name} now signs with ${key.kid}, replacing ${key.supersedes}. ` +
                `Credentials signed with the old key still verify — the key valid on the award ` +
                `date is the one that is checked.`
            : `${institution.name} now signs with ${key.kid} and can issue credentials.`
        );
        this.load();
      },
      error: (failure: unknown) => {
        this.keying.set(null);
        this.error.set(describeFailure(failure));
      }
    });
  }

  /**
   * Whether accreditation has lapsed as at today.
   *
   * The authoritative check lives in the backend domain — this is presentation only, and the
   * screen never decides eligibility on its own.
   */
  protected hasLapsed(institution: Institution): boolean {
    return institution.accreditedUntil < new Date().toISOString().slice(0, 10);
  }
}
