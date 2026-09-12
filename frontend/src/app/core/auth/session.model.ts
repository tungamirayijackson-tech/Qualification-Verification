/**
 * The four roles (§01, FR-10), as the access token names them.
 *
 * Mirrors the backend enum. The console uses this only to decide what to *show*; it is never
 * the authorisation boundary. Every screen behind a guard is also behind a server-side role
 * check, because a guard is a convenience for the user and an attacker simply does not run it.
 */
export type Role = 'REGISTRAR' | 'VERIFIER' | 'AUDITOR' | 'ADMIN';

/** A signed-in user, as decoded from the access token. */
export interface CurrentUser {
  readonly userId: string;
  readonly email: string;
  readonly role: Role;
  /** The institution a registrar is bound to; null for cross-institution roles. */
  readonly institutionId: string | null;
}

/** The token pair the API issues on a successful sign-in. */
export interface TokenPair {
  readonly accessToken: string;
  readonly refreshToken: string;
  readonly accessExpiresAt: string;
  readonly tokenType: string;
}

/**
 * What the API answers when a role that requires MFA has not enrolled yet.
 *
 * A 428, not a 401: the password was right and there is a specific next step. Modelling it as
 * its own shape rather than an error keeps that distinction visible in the console too.
 */
export interface EnrolmentRequired {
  readonly message: string;
  readonly mfaSecret: string;
  readonly issuer: string;
}

/** The outcome of a sign-in attempt that did not fail. */
export type SignInOutcome =
  | { readonly kind: 'signed-in'; readonly user: CurrentUser }
  | {
      readonly kind: 'enrol-mfa';
      readonly secret: string;
      readonly email: string;
    };
