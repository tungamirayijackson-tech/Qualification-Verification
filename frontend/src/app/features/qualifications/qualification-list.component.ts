import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Institution } from '../../core/api/institution';
import { InstitutionService } from '../../core/api/institution.service';
import { describeFailure } from '../../core/api/problem';
import { Qualification } from '../../core/api/qualification';
import { QualificationService } from '../../core/api/qualification.service';
import { SessionService } from '../../core/auth/session.service';
import { matchesFilter } from '../../shared/fold-text';
import { IconComponent } from '../../shared/icon.component';

type LoadState = 'loading' | 'ready' | 'failed';

/**
 * What each awarding body offers, and how a new one is recorded.
 *
 * This screen exists as much for usability as for administration. Registering an award needs a
 * qualification's identity, and before this the console asked a registrar to type a UUID they
 * had no way of looking up — a register that expects somebody to know a UUID by heart is a
 * register that will be given the wrong one.
 *
 * A registrar sees their own institution and no other; the API takes the scope from their token
 * and ignores anything the client sends. An administrator is not scoped, so they pick.
 */
@Component({
  selector: 'app-qualification-list',
  standalone: true,
  imports: [CommonModule, FormsModule, IconComponent],
  templateUrl: './qualification-list.component.html',
  styleUrl: './qualification-list.component.scss'
})
export class QualificationListComponent {
  private readonly qualifications = inject(QualificationService);
  private readonly institutionService = inject(InstitutionService);
  private readonly session = inject(SessionService);

  /**
   * Whether this user must name an institution.
   *
   * The question is "is this account bound to one institution", not "is this an administrator" —
   * which is what it used to ask, and the difference is a bug an auditor met head-on. A
   * registrar's institution comes from their token. An **auditor** is deliberately
   * cross-institution, exactly like an administrator, so the API refuses to guess for them and
   * the screen has to ask. Asking "is this an admin" left auditors sending no institution at
   * all, and every visit to this page answered 400.
   */
  protected readonly mustChooseInstitution = !this.session.hasRole('REGISTRAR');

  /**
   * Only a registrar records what their institution offers.
   *
   * <p>Not an administrator: admitting an institution to the register and giving it a signing
   * key is administration, and deciding what that institution awards is the institution's own
   * business. An auditor writes nothing, ever. So this is one role, and the API agrees — the
   * POST is REGISTRAR-only, and a button for anybody else could produce nothing but a 403.
   */
  protected readonly canAdd = this.session.hasRole('REGISTRAR');

  /**
   * Who the identity on each row is actually useful to.
   *
   * Only a registrar awards credentials — not an administrator, and certainly not an auditor —
   * so only a registrar is told that is what the id is for. Telling everybody was the same
   * mistake in miniature as asking an auditor for an institution they do not have.
   */
  protected readonly canRegisterAwards = this.session.hasRole('REGISTRAR');

  private readonly allRows = signal<Qualification[]>([]);

  /**
   * What the user has typed into the filter box.
   *
   * Filtered in the browser: one institution's prospectus is already on the page, and asking
   * the server again on every keystroke would re-fetch rows it just sent.
   */
  protected readonly filterTerm = signal('');

  /** The rows to draw, narrowed by the filter, ignoring case and accents. */
  protected readonly rows = computed(() => {
    const term = this.filterTerm();
    return this.allRows().filter((qualification) =>
      matchesFilter(
        term,
        qualification.title,
        qualification.saqaQualId,
        String(qualification.nqfLevel)
      )
    );
  });

  protected readonly isFiltered = computed(() => this.filterTerm().trim().length > 0);
  protected readonly totalLoaded = computed(() => this.allRows().length);
  protected readonly institutions = signal<Institution[]>([]);
  protected readonly state = signal<LoadState>('loading');

  protected readonly adding = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly note = signal<string | null>(null);
  protected readonly copied = signal<string | null>(null);

  /** Only an administrator chooses; a registrar's institution comes from their token. */
  protected selectedInstitution = '';

  protected title = '';
  protected nqfLevel = 7;
  protected credits = 360;
  protected saqaQualId = '';

  constructor() {
    if (this.mustChooseInstitution) {
      this.institutionService.list().subscribe({
        next: (found) => {
          this.institutions.set(found);
          this.selectedInstitution = found[0]?.id ?? '';
          this.load();
        },
        error: () => this.state.set('failed')
      });
    } else {
      this.load();
    }
  }

  protected load(): void {
    if (this.mustChooseInstitution && !this.selectedInstitution) {
      this.allRows.set([]);
      this.state.set('ready');
      return;
    }

    this.state.set('loading');
    this.qualifications
      .list(this.mustChooseInstitution ? this.selectedInstitution : undefined)
      .subscribe({
        next: (found) => {
          this.allRows.set(found);
          this.state.set('ready');
        },
        error: () => this.state.set('failed')
      });
  }

  /** Called on every keystroke in the filter box. */
  protected onFilter(term: string): void {
    this.filterTerm.set(term);
  }

  protected toggleAdding(): void {
    this.adding.update((open) => !open);
    this.error.set(null);
    this.note.set(null);
  }

  protected add(): void {
    this.busy.set(true);
    this.error.set(null);
    this.note.set(null);

    this.qualifications
      .add({
        title: this.title.trim(),
        nqfLevel: this.nqfLevel,
        credits: this.credits,
        saqaQualId: this.saqaQualId.trim() || undefined
      })
      .subscribe({
        next: (added) => {
          this.busy.set(false);
          this.adding.set(false);
          this.note.set(`"${added.title}" is recorded and can now be awarded.`);
          this.title = '';
          this.saqaQualId = '';
          this.load();
        },
        error: (failure: unknown) => {
          this.busy.set(false);
          this.error.set(describeFailure(failure));
        }
      });
  }

  /**
   * Copies a qualification's identity, which is what the register-an-award screen asks for.
   *
   * The clipboard can be refused -- a browser may withhold it outside a secure context -- so a
   * failure leaves the id on screen to be selected by hand rather than pretending it worked.
   */
  protected copyId(qualification: Qualification): void {
    navigator.clipboard
      ?.writeText(qualification.id)
      .then(() => {
        this.copied.set(qualification.id);
        setTimeout(() => this.copied.set(null), 2000);
      })
      .catch(() => this.error.set('The browser would not give access to the clipboard.'));
  }
}
