import { CommonModule } from '@angular/common';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Observable, Subject, catchError, debounceTime, filter, map, of, switchMap } from 'rxjs';

import { describeFailure } from '../../core/api/problem';
import { CredentialSearchResults } from '../../core/api/search';
import { SearchService } from '../../core/api/search.service';
import { SessionService } from '../../core/auth/session.service';
import { IconComponent } from '../../shared/icon.component';
import { isWellFormedNationalId } from '../../shared/national-id';

type State = 'idle' | 'searching' | 'answered' | 'failed';

/** How long to wait after the last keystroke before asking the server. */
const TYPING_PAUSE_MS = 300;

/** Marks a request that failed, so the stream survives it. */
const FAILED = Symbol('failed');

/**
 * FR-03: find qualification records.
 *
 * <p>Three things are worth reading as design rather than markup.
 *
 * The search <b>runs as the user types</b>, not when they press a button. Three pieces make that
 * safe rather than merely fast:
 *
 * <ul>
 *   <li>a pause of {@link TYPING_PAUSE_MS} after the last keystroke, so typing a nine-letter
 *       surname is one request rather than nine;</li>
 *   <li>a comparison of the whole criteria set against what was last asked, so a keystroke that
 *       does not change the query — moving the caret, retyping the same letter — asks nothing;</li>
 *   <li>{@code switchMap}, which <b>cancels a request still in flight</b> when newer criteria
 *       arrive. Without it the results shown are whichever response happens to land last, and a
 *       slow early request can overwrite a fast later one — the classic search-as-you-type bug,
 *       and the one that is hardest to notice because it needs a slow network to appear.</li>
 * </ul>
 *
 * <p>Case and accents are folded <b>by the server</b>, which is where it has to happen: the
 * trigram index is built over a folded column, so folding anywhere else would search one form
 * against another. "MAHLANGU", "mahlangu" and "Mahlangu" are the same query, and so are "renee"
 * and "Renée". The button remains, because pressing Enter or clicking Search should still work
 * and because a control that vanishes when a feature becomes automatic is a control people
 * looked for.
 *
 * <p>There is <b>no institution filter</b>. A registrar's search is pinned to their own
 * institution by the server, from their token, so offering the control would be offering a
 * choice the API ignores. An auditor searches across all of them and the screen says so.
 *
 * <p>The <b>national-ID lookup is a separate mode</b> rather than another box on the same form.
 * It goes to a different endpoint over POST, because an identifier in a query string is copied
 * into proxy logs and browser history. Keeping it visibly separate stops it drifting into the
 * general filter row later, where it would quietly become a GET parameter.
 */
@Component({
  selector: 'app-search-credentials',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, IconComponent],
  templateUrl: './search-credentials.component.html',
  styleUrl: './search-credentials.component.scss'
})
export class SearchCredentialsComponent {
  private readonly search = inject(SearchService);
  private readonly session = inject(SessionService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly state = signal<State>('idle');
  protected readonly results = signal<CredentialSearchResults | null>(null);
  protected readonly error = signal<string | null>(null);

  /** Auditors search every institution; registrars are pinned to their own. */
  protected readonly searchesAllInstitutions = this.session.hasRole('AUDITOR');

  protected mode: 'criteria' | 'holder' = 'criteria';
  protected holderName = '';
  protected nqfLevel: number | null = null;
  protected status: 'ISSUED' | 'REVOKED' | null = null;
  protected awardedFrom = '';
  protected awardedTo = '';
  protected holderNationalId = '';

  protected readonly nqfLevels = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

  /** Every edit to the form pushes here; the pipeline below decides what to do about it. */
  private readonly criteriaChanged = new Subject<void>();

  constructor() {
    this.criteriaChanged
      .pipe(
        debounceTime(TYPING_PAUSE_MS),
        map(() => this.criteriaKey()),
        // The whole criteria set, compared as one string: a keystroke that does not change what
        // would be asked -- retyping the same letter, moving the caret -- asks nothing.
        filter((key) => key !== this.lastAsked),
        switchMap(() => this.runCurrentSearch(0)),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((outcome) => this.applyOutcome(outcome));

    // Arrive with the register already showing. A screen that filters as you type but starts
    // blank makes the first keystroke feel like a different feature from the second.
    this.criteriaChanged.next();
  }

  /** The criteria the last request was made with, so an identical one is not repeated. */
  private lastAsked: string | null = null;

  /** Called from the template whenever any control changes. */
  protected onCriteriaChange(): void {
    this.criteriaChanged.next();
  }

  /** Switches between name-and-filters and national-ID lookup. */
  protected setMode(mode: 'criteria' | 'holder'): void {
    if (this.mode === mode) {
      return;
    }
    this.mode = mode;
    this.error.set(null);
    this.criteriaChanged.next();
  }

  /** The Search button and the Enter key. Runs immediately, without waiting for the pause. */
  protected submit(): void {
    this.runCurrentSearch(0)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((outcome) => this.applyOutcome(outcome));
  }

  protected goToPage(page: number): void {
    if (page < 0) {
      return;
    }
    // Paging is a deliberate act, so it is not debounced -- a 300 ms wait after clicking Next
    // reads as a slow application rather than as a considered pause.
    this.runCurrentSearch(page)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((outcome) => this.applyOutcome(outcome));
  }

  protected clear(): void {
    this.holderName = '';
    this.nqfLevel = null;
    this.status = null;
    this.awardedFrom = '';
    this.awardedTo = '';
    this.holderNationalId = '';
    this.error.set(null);
    this.criteriaChanged.next();
  }

  /** Whether the form has enough in it to be worth submitting. */
  protected canSubmit(): boolean {
    if (this.mode === 'holder') {
      return isWellFormedNationalId(this.holderNationalId);
    }
    // An entirely empty criteria search is allowed: within an institution it is a reasonable
    // "show me everything" request, and the server pages it either way.
    return true;
  }

  /** One-based page number, for a human reading the results count. */
  protected humanPage(results: CredentialSearchResults): string {
    return `Page ${results.page + 1} of ${Math.max(results.totalPages, 1)}`;
  }

  /**
   * Runs the search the form currently describes.
   *
   * <p>Never errors: a failure is turned into a value, because an error reaching the outer
   * pipeline would complete it and the screen would stop searching for the rest of the session.
   */
  private runCurrentSearch(
    page: number
  ): Observable<CredentialSearchResults | typeof FAILED | null> {
    if (!this.canSubmit()) {
      // A partial national ID is not a query. Say nothing rather than ask for everything.
      this.state.set('idle');
      this.results.set(null);
      return of(null);
    }

    this.lastAsked = this.criteriaKey();
    this.state.set('searching');
    this.error.set(null);

    const request =
      this.mode === 'holder'
        ? this.search.searchByHolder(this.holderNationalId.trim(), page)
        : this.search.search({
            holderName: this.holderName,
            nqfLevel: this.nqfLevel,
            status: this.status,
            awardedFrom: this.awardedFrom || null,
            awardedTo: this.awardedTo || null,
            page
          });

    return request.pipe(
      catchError((failure: unknown) => {
        this.error.set(describeFailure(failure));
        return of(FAILED);
      })
    );
  }

  private applyOutcome(outcome: CredentialSearchResults | typeof FAILED | null): void {
    if (outcome === null) {
      return;
    }
    if (outcome === FAILED) {
      this.state.set('failed');
      return;
    }
    this.results.set(outcome);
    this.state.set('answered');
  }

  /** Everything that would change the request, as one comparable string. */
  private criteriaKey(): string {
    return [
      this.mode,
      this.holderName.trim(),
      this.nqfLevel ?? '',
      this.status ?? '',
      this.awardedFrom,
      this.awardedTo,
      this.holderNationalId.trim()
    ].join('\u0000');
  }
}
