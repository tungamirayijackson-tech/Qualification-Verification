/** The four independent checks a verdict is the conjunction of (§05). */
export type CheckName = 'signature' | 'issuerStanding' | 'revocation' | 'ledgerPresence';

/** The outcome of one check. */
export type CheckOutcome = 'PASS' | 'FAIL' | 'NOT_RUN';

/**
 * What a public verification returns.
 *
 * Everything except `verdict` and `verifiedAt` is absent on the not-found path, and that is the
 * contract rather than an accident: an unknown token, an expired one, a withdrawn one and a
 * credential that failed an integrity check all produce the identical body, so the endpoint
 * cannot be used to work out which of those happened.
 */
export interface VerificationResult {
  readonly verdict: 'VALID' | 'REVOKED' | 'NOT_FOUND';
  readonly checks?: Readonly<Record<CheckName, CheckOutcome>>;
  readonly qualification?: string;
  readonly nqfLevel?: number;
  readonly institution?: string;
  readonly awardedOn?: string;
  /** The most that is ever disclosed about the person (NFR-06). */
  readonly holderInitials?: string;
  readonly revokedReason?: string;
  readonly revokedAt?: string;
  readonly verifiedAt: string;
  /** Citable in a dispute: the ledger entry that records this very check. */
  readonly ledgerSeq?: number;
}

/** The checks in the order they are always displayed, with their wording. */
export const CHECK_LABELS: readonly {
  readonly key: CheckName;
  readonly label: string;
}[] = [
  { key: 'signature', label: 'Signature verifies against the published key' },
  { key: 'issuerStanding', label: 'Issuer had standing on the award date' },
  { key: 'revocation', label: 'Not revoked' },
  { key: 'ledgerPresence', label: 'Present in the audit chain' }
];
