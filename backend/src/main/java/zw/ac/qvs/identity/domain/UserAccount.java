package zw.ac.qvs.identity.domain;

import java.util.UUID;

/**
 * A person who can sign in.
 *
 * <p>The invariants here mirror the CHECK constraints on {@code app_user}, deliberately. A rule
 * enforced only in Java is a rule that a migration or a support script can bypass; a rule
 * enforced only in SQL produces an error message nobody can act on. Both layers, both tested.
 *
 * @param id            surrogate identity
 * @param email         login identifier, unique
 * @param displayName   how they are shown in the console and named in audit entries
 * @param role          what they may do
 * @param institutionId the institution they act for; required for a registrar, forbidden for an auditor
 * @param passwordHash  bcrypt hash; the password itself never exists outside one request
 * @param mfaSecret     TOTP shared secret, null until enrolled
 * @param mfaEnrolled   whether MFA has been set up
 * @param disabled      whether the account has been suspended
 */
public record UserAccount(
        UUID id,
        String email,
        String displayName,
        Role role,
        UUID institutionId,
        String passwordHash,
        String mfaSecret,
        boolean mfaEnrolled,
        boolean disabled) {

    public UserAccount {
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("a usable email address is required");
        }
        if (role == null) {
            throw new IllegalArgumentException("role is required");
        }
        if (role.isInstitutionScoped() && institutionId == null) {
            // A registrar with no institution is a registrar who could act for anyone, which
            // is precisely the thing the trust model forbids.
            throw new IllegalArgumentException("a registrar must be bound to one institution");
        }
        if (role == Role.AUDITOR && institutionId != null) {
            // An auditor is deliberately cross-institution. Pinning one to a single
            // institution would quietly narrow the scope of every audit they run.
            throw new IllegalArgumentException("an auditor is not scoped to an institution");
        }
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new IllegalArgumentException("password hash is required");
        }
    }

    /**
     * Whether this account may currently be used to sign in.
     *
     * @return true when enabled and, where required, enrolled in MFA
     */
    public boolean canSignIn() {
        return !disabled && (!role.requiresMfa() || mfaEnrolled);
    }

    /**
     * Whether a second factor must be presented at sign-in.
     *
     * @return true when the account has MFA enrolled
     */
    public boolean requiresSecondFactor() {
        return mfaEnrolled && mfaSecret != null;
    }

    /**
     * The same account, suspended or restored.
     *
     * <p>An account is never deleted. A registrar who has left still signed the credentials
     * they signed, and the ledger names them; removing the row would leave entries pointing at
     * nobody and make the trail harder to read, not cleaner.
     *
     * @param suspended whether sign-in should be refused
     * @return a copy with the access flag set
     */
    public UserAccount withAccess(boolean suspended) {
        return new UserAccount(id, email, displayName, role, institutionId, passwordHash,
                mfaSecret, mfaEnrolled, suspended);
    }

    /**
     * The same account with a different password.
     *
     * @param newPasswordHash the bcrypt hash of the new password
     * @return a copy carrying the new hash
     */
    public UserAccount withPasswordHash(String newPasswordHash) {
        return new UserAccount(id, email, displayName, role, institutionId, newPasswordHash,
                mfaSecret, mfaEnrolled, disabled);
    }

    /**
     * The same account with its second factor cleared.
     *
     * <p>The secret is dropped, not kept and disabled. A retained secret is one somebody could
     * still be holding on a device that is out of the owner's hands — which is the situation a
     * reset exists to end. The next sign-in enrols a fresh one.
     *
     * @return a copy with no second factor
     */
    public UserAccount withoutSecondFactor() {
        return new UserAccount(id, email, displayName, role, institutionId, passwordHash,
                null, false, disabled);
    }
}
