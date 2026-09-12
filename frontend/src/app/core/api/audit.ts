/** One ledger entry, as an auditor sees it (FR-08). */
export interface AuditEntry {
  readonly seq: number;
  readonly occurredAt: string;
  readonly actorRole: string;
  readonly action: string;
  readonly subjectRef: string;
  readonly payloadHash: string;
  readonly prevHash: string;
  readonly entryHash: string;
  /** Whether this entry still hashes to its stored value. */
  readonly intact: boolean;
}

/**
 * The result of recomputing the chain.
 *
 * `brokenAtSeq` is the whole point. "The audit log may have been tampered with" is not
 * actionable; "the history is trustworthy up to seq 4182 and not after it" is.
 */
export interface ChainVerification {
  readonly intact: boolean;
  readonly headSequence: number;
  readonly brokenAtSeq: number | null;
  readonly breakKind: string | null;
  readonly detail: string;
}
