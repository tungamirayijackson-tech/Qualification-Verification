/** The four powers in the system. */
export type Role = 'REGISTRAR' | 'VERIFIER' | 'AUDITOR' | 'ADMIN';

/**
 * An account, as an administrator sees it.
 *
 * Mirrors `UserResponse`. Read it as a list of omissions: no password hash and no MFA secret,
 * because an endpoint that returned either would undo the reason both exist.
 */
export interface User {
  readonly id: string;
  readonly email: string;
  readonly displayName: string;
  readonly role: Role;
  readonly institutionId: string | null;
  readonly mfaEnrolled: boolean;
  readonly disabled: boolean;
}

/** What an administrator sends. There is deliberately no password field. */
export interface CreateUserRequest {
  readonly email: string;
  readonly displayName: string;
  readonly role: Role;
  readonly institutionId?: string;
}

/**
 * A new account and the one and only sight of its password.
 *
 * The password is stored only as a hash, so the system genuinely cannot show it again — which
 * is why the screen tells the administrator to pass it on now.
 */
export interface CreatedUser {
  readonly account: User;
  readonly initialPassword: string;
}
