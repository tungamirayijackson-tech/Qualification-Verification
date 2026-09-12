package zw.ac.qvs.identity.application;

import java.time.Clock;
import java.time.Instant;
import zw.ac.qvs.identity.domain.Totp;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;

/**
 * Signing in, with a second factor where the role requires one.
 *
 * <p>Failures are deliberately uniform. A wrong password, an unknown email, a disabled account
 * and a wrong TOTP code all produce the same {@link AuthenticationFailed} with the same
 * message, because distinguishing them turns the login form into a directory of who holds an
 * account. The distinction that operators need is preserved where it belongs — in the ledger
 * entry, which records the failure and its cause.
 *
 * <p>The password hash is verified even when the account does not exist, so that a request for
 * an unknown user takes the same time as one for a known user with the wrong password. Without
 * that, response timing alone enumerates the user table.
 */
public class AuthenticateUser {

    /**
     * A bcrypt hash of an unguessable value, used to spend the same time verifying a password
     * for an account that does not exist as for one that does.
     */
    private static final String DUMMY_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final PasswordHasher passwords;
    private final TokenIssuer tokens;
    private final AppendEntry ledger;
    private final Clock clock;

    public AuthenticateUser(
            UserRepository users,
            PasswordHasher passwords,
            TokenIssuer tokens,
            AppendEntry ledger,
            Clock clock) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.ledger = ledger;
        this.clock = clock;
    }

    /** Raised for every kind of failed sign-in, with the same message for all of them. */
    public static class AuthenticationFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AuthenticationFailed() {
            super("Those credentials were not accepted.");
        }
    }

    /**
     * The outcome of a sign-in attempt that was not a failure.
     *
     * <p>A result type rather than an exception, and the reason is worth recording: needing to
     * enrol a second factor is a <em>state</em>, not an error. Modelling it as a thrown
     * exception meant the freshly generated secret was written inside the request's
     * transaction and then rolled back by the throw — so the server handed the user a secret
     * it had immediately forgotten, and every enrolment attempt failed. Expected outcomes
     * belong in the return type.
     */
    public sealed interface SignInResult {

        /** The user is signed in. */
        record Authenticated(TokenIssuer.Tokens tokens) implements SignInResult {
        }

        /**
         * The password was right, but this role cannot be used until MFA is set up.
         *
         * @param secret the newly generated shared secret, shown once
         */
        record EnrolmentRequired(String secret) implements SignInResult {
        }
    }

    /**
     * What a sign-in attempt carries.
     *
     * @param email    the login identifier
     * @param password the password
     * @param totpCode the second factor, null when the account has none enrolled
     */
    public record Command(String email, String password, String totpCode) {
    }

    /**
     * Signs a user in.
     *
     * @param command the attempt
     * @return tokens, or an instruction to enrol a second factor first
     */
    public SignInResult signIn(Command command) {
        Instant now = Instant.now(clock);
        var found = users.findByEmail(normalise(command.email()));

        // Always verify against something, so that an unknown email and a wrong password cost
        // the same amount of time.
        String hash = found.map(UserAccount::passwordHash).orElse(DUMMY_HASH);
        boolean passwordOk = passwords.matches(command.password(), hash);

        if (found.isEmpty() || !passwordOk) {
            recordFailure(command.email(), found.isEmpty() ? "UNKNOWN_USER" : "BAD_PASSWORD");
            throw new AuthenticationFailed();
        }

        UserAccount account = found.get();
        if (account.disabled()) {
            recordFailure(command.email(), "DISABLED");
            throw new AuthenticationFailed();
        }

        if (account.role().requiresMfa() && !account.mfaEnrolled()) {
            // The account is real and the password was right, but the role cannot be used
            // until a second factor exists. Enrolment is offered rather than the sign-in
            // simply failing, because this is a setup step and not an attack.
            //
            // An existing half-finished enrolment reuses its secret. Generating a fresh one
            // on every attempt would invalidate the QR code the user just scanned, so a
            // second try could never succeed.
            String secret = account.mfaSecret() != null ? account.mfaSecret()
                    : Totp.generateSecret();
            if (account.mfaSecret() == null) {
                users.save(new UserAccount(account.id(), account.email(), account.displayName(),
                        account.role(), account.institutionId(), account.passwordHash(),
                        secret, false, account.disabled()));
            }
            return new SignInResult.EnrolmentRequired(secret);
        }

        if (account.requiresSecondFactor()
                && !Totp.verify(account.mfaSecret(), command.totpCode(), now)) {
            recordFailure(command.email(), "BAD_SECOND_FACTOR");
            throw new AuthenticationFailed();
        }

        ledger.record(account.id(), account.role().name(), LedgerAction.USER_LOGGED_IN,
                account.email(), "signed in");

        return new SignInResult.Authenticated(tokens.issue(account));
    }

    /**
     * Completes MFA enrolment by proving the user can generate a code from the new secret.
     *
     * @param email    the account
     * @param totpCode a code generated from the secret handed out at enrolment
     * @return true when enrolment completed
     */
    public boolean completeMfaEnrolment(String email, String totpCode) {
        UserAccount account = users.findByEmail(normalise(email))
                .orElseThrow(AuthenticationFailed::new);

        if (account.mfaSecret() == null
                || !Totp.verify(account.mfaSecret(), totpCode, Instant.now(clock))) {
            return false;
        }

        users.save(new UserAccount(account.id(), account.email(), account.displayName(),
                account.role(), account.institutionId(), account.passwordHash(),
                account.mfaSecret(), true, account.disabled()));
        return true;
    }

    private void recordFailure(String email, String cause) {
        // The ledger keeps the detail the response withholds. A run of BAD_PASSWORD entries
        // against one account is a brute-force attempt; a run of UNKNOWN_USER entries is
        // someone working through a list of addresses. The login form must not tell them
        // apart, but an operator must be able to.
        ledger.recordAnonymous(LedgerAction.USER_LOGIN_FAILED,
                email == null ? "unknown" : normalise(email), cause);
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
