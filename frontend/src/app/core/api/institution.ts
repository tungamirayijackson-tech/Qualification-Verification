/**
 * An awarding body, as the API returns it.
 *
 * Mirrors `InstitutionResponse` on the backend. The console never invents fields the API does
 * not send: if a screen needs data, the API contract changes first and the generated OpenAPI
 * diff makes that visible in the pull request.
 */
export interface Institution {
  readonly id: string;
  readonly name: string;
  readonly country: string;
  readonly providerNumber: string;
  /** ISO date. Compared, never parsed into a Date for display arithmetic. */
  readonly accreditedUntil: string;
  /** Null until the institution has been onboarded with a signing key. */
  readonly activeKeyId: string | null;
}

/** What an administrator sends to admit an awarding body. */
export interface OnboardInstitutionRequest {
  readonly name: string;
  readonly country: string;
  readonly providerNumber: string;
  /** ISO date. Must be in the future: an institution cannot be admitted already lapsed. */
  readonly accreditedUntil: string;
}

/**
 * A newly issued signing key.
 *
 * The public half only. The private key never leaves the vault, and an administrator has no
 * use for one: the system signs on the institution's behalf, and a key an operator could copy
 * out is a key that can be copied out.
 */
export interface SigningKey {
  readonly kid: string;
  readonly validFrom: string;
  readonly publicJwk: string;
  /** The key this one replaced, or null when it is the institution's first. */
  readonly supersedes: string | null;
}
