/** One row of search results (FR-03). */
export interface CredentialMatch {
  readonly serial: string;
  /** Initials only. The API never returns a holder's full name to any screen. */
  readonly holderInitials: string;
  readonly qualification: string;
  readonly nqfLevel: number;
  readonly institution: string;
  readonly awardedOn: string;
  readonly status: 'ISSUED' | 'REVOKED';
}

/**
 * A page of matches.
 *
 * The paging metadata travels with the rows because a console that cannot tell the user how
 * many matches exist has to choose between a "next" button that sometimes leads nowhere and no
 * paging at all.
 */
export interface CredentialSearchResults {
  readonly results: readonly CredentialMatch[];
  readonly page: number;
  readonly size: number;
  readonly totalRows: number;
  readonly totalPages: number;
  readonly hasMore: boolean;
}

/** What the search form submits. Every field is optional. */
export interface CredentialSearchCriteria {
  readonly holderName?: string;
  readonly nqfLevel?: number | null;
  readonly status?: 'ISSUED' | 'REVOKED' | null;
  readonly awardedFrom?: string | null;
  readonly awardedTo?: string | null;
  readonly page?: number;
  readonly size?: number;
}
