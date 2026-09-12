package zw.ac.qvs.identity.adapter.in;

import java.util.UUID;
import zw.ac.qvs.identity.domain.UserAccount;

/**
 * An account, as an administrator sees it.
 *
 * <p>Read this as a list of omissions. There is no password hash and no MFA secret, and the
 * omission is made here rather than in the repository so that it is visible in the file an
 * assessor opens to check it. A hash is not a password, but it is offline-guessable, and an
 * MFA secret is the second factor itself -- an endpoint that returned either would undo the
 * reason both exist.
 *
 * @param id            the account
 * @param email         the address they sign in with
 * @param displayName   how they are named
 * @param role          what they may do
 * @param institutionId the institution a registrar acts for, or null
 * @param mfaEnrolled   whether they have completed second-factor enrolment
 * @param disabled      whether the account has been turned off
 */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        UUID institutionId,
        boolean mfaEnrolled,
        boolean disabled) {

    static UserResponse from(UserAccount account) {
        return new UserResponse(
                account.id(),
                account.email(),
                account.displayName(),
                account.role().name(),
                account.institutionId(),
                account.mfaEnrolled(),
                account.disabled());
    }
}
