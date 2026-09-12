/**
 * Why a credential was withdrawn.
 *
 * A closed set, mirroring the backend enum and the database CHECK constraint. Free text would
 * give a verifier nothing they could act on without reading prose, and would make "how many
 * revocations were for misconduct" unanswerable.
 */
export type RevocationReason =
  | 'ADMINISTRATIVE_ERROR'
  | 'ACADEMIC_MISCONDUCT'
  | 'QUALIFICATION_WITHDRAWN'
  | 'ISSUED_IN_ERROR'
  | 'HOLDER_REQUEST'
  | 'OTHER';

/** The reason codes with the wording a registrar sees. */
export const REVOCATION_REASONS: readonly {
  readonly value: RevocationReason;
  readonly label: string;
}[] = [
  { value: 'ADMINISTRATIVE_ERROR', label: 'Administrative error' },
  { value: 'ACADEMIC_MISCONDUCT', label: 'Academic misconduct' },
  { value: 'QUALIFICATION_WITHDRAWN', label: 'Qualification withdrawn' },
  { value: 'ISSUED_IN_ERROR', label: 'Issued in error' },
  { value: 'HOLDER_REQUEST', label: 'At the holder’s request' },
  { value: 'OTHER', label: 'Other (explain in the note)' }
];

/** What the console submits to register a qualification (FR-01). */
export interface RegisterCredentialRequest {
  readonly qualificationId: string;
  /** Hashed the moment it reaches the server, and never stored. */
  readonly holderNationalId: string;
  readonly holderName: string;
  readonly holderDateOfBirth?: string | null;
  readonly awardedOn: string;
}

/** What the API returns after a write. */
export interface CredentialSummary {
  readonly serial: string;
  readonly awardedOn: string;
  readonly status: 'ISSUED' | 'REVOKED';
  readonly keyId: string;
  readonly revokedAt: string | null;
  readonly revokedReason: RevocationReason | null;
  readonly issuedAt: string;
}

/**
 * One credential in full, for a registrar or auditor (FR-04).
 *
 * Carries the signature and the exact signed bytes on purpose: an auditor must be able to
 * verify the signature independently, with their own tooling, rather than taking this system's
 * word for it. The holder appears as initials even here — no console screen needs the full
 * name back out of storage.
 */
export interface CredentialDetail {
  readonly serial: string;
  readonly institution: string;
  readonly qualification: string;
  readonly nqfLevel: number;
  readonly awardedOn: string;
  readonly holderInitials: string;
  readonly status: 'ISSUED' | 'REVOKED';
  readonly revokedAt: string | null;
  readonly revokedReason: RevocationReason | null;
  readonly keyId: string;
  readonly detachedJws: string;
  readonly payloadCanonical: string;
}

/** A minted share token. The secret is shown once and cannot be retrieved again. */
export interface ShareTokenIssued {
  readonly token: string;
  readonly verifyUrl: string;
  readonly tokenId: string;
  readonly expiresAt: string;
  readonly serial: string;
}
