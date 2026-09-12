package zw.ac.qvs.identity.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;

/**
 * What an administrator can do to an account after it has been created.
 *
 * <p>Creating accounts was, until this existed, the only thing anybody could do to one. That
 * left three holes with real consequences. A registrar who left the university kept signing in
 * and kept being able to sign credentials, because nothing could set the {@code disabled} flag
 * that sign-in already checked. Anyone who lost their initial password before using it had a
 * dead account and no way back. Anyone who lost the phone holding their second factor was
 * locked out permanently — which happened during development and had to be repaired with SQL
 * against the database, a repair not available to an administrator using the system as built.
 *
 * <h2>Nothing is deleted, and nothing is silent</h2>
 *
 * <p>An account is suspended, never removed. A registrar who has left still signed what they
 * signed, and the ledger names them; deleting the row would leave audit entries pointing at
 * nobody. Every operation here writes its own ledger entry naming the administrator who did it
 * and the account it was done to, because "who took away their access, and when" is the same
 * kind of question as "who let them in".
 *
 * <h2>Two guards on disabling</h2>
 *
 * <p>An administrator cannot disable themselves, and the last enabled administrator cannot be
 * disabled at all. Both exist to prevent the same accident: a system with no administrator left
 * in it cannot create one, because creating accounts requires an administrator. The recovery
 * would be an operator with database access, which is precisely the position the bootstrap was
 * written to avoid anybody needing.
 */
public class ManageAccount {

    private static final Logger log = LoggerFactory.getLogger(ManageAccount.class);

    private final UserRepository users;
    private final PasswordHasher passwords;
    private final TokenIssuer tokens;
    private final AppendEntry ledger;

    public ManageAccount(UserRepository users, PasswordHasher passwords, TokenIssuer tokens,
            AppendEntry ledger) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.ledger = ledger;
    }

    /** Refused for a reason the administrator can act on. */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    /** Asked about an account that is not in the register. */
    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /**
     * A new password, shown once.
     *
     * @param account     the account it belongs to
     * @param newPassword the password, which this system will never show again
     */
    public record PasswordReset(UserAccount account, String newPassword) {
    }

    /**
     * Suspends an account, so it can no longer sign in.
     *
     * <p>Existing sessions are ended as far as they can be: every refresh token is revoked, so
     * no new tokens can be minted. The access token already in the holder's hands keeps working
     * until it expires, which is at most fifteen minutes — see {@link TokenIssuer}, where the
     * reason that trade is made deliberately is written down.
     *
     * @param targetId  whose access to withdraw
     * @param actorId   the administrator doing it
     * @param actorRole the role recorded in the ledger
     * @return the suspended account
     * @throws Rejected when the account is already suspended, is the actor's own, or is the
     *                  last administrator anybody could sign in as
     * @throws NotFound when no such account exists
     */
    public UserAccount disable(UUID targetId, UUID actorId, String actorRole) {
        UserAccount target = require(targetId);

        if (target.disabled()) {
            throw new Rejected(target.email() + " is already suspended.");
        }
        if (target.id().equals(actorId)) {
            // Refused rather than allowed-with-a-warning: an administrator who does this to
            // themselves cannot undo it, because undoing it requires an administrator.
            throw new Rejected("An administrator cannot suspend their own account. Ask another "
                    + "administrator to do it, so somebody is always able to undo it.");
        }
        requireAnotherAdministratorRemains(target);

        UserAccount suspended = users.save(target.withAccess(true));
        int ended = tokens.revokeSessionsFor(target.id());

        log.info("account {} suspended, {} session(s) ended", suspended.email(), ended);
        ledger.record(actorId, actorRole, LedgerAction.USER_DISABLED, suspended.id().toString(),
                suspended.email() + "|" + suspended.role() + "|sessions=" + ended);
        return suspended;
    }

    /**
     * Restores a suspended account.
     *
     * <p>Deliberately not a password reset as well. Somebody returning from leave signs in with
     * what they had; somebody whose account was suspended because their password was in the
     * wrong hands needs the reset done explicitly, and an administrator should have to say so.
     *
     * @param targetId  whose access to give back
     * @param actorId   the administrator doing it
     * @param actorRole the role recorded in the ledger
     * @return the restored account
     * @throws Rejected when the account was not suspended in the first place
     * @throws NotFound when no such account exists
     */
    public UserAccount restore(UUID targetId, UUID actorId, String actorRole) {
        UserAccount target = require(targetId);

        if (!target.disabled()) {
            throw new Rejected(target.email() + " is not suspended.");
        }

        UserAccount restored = users.save(target.withAccess(false));

        log.info("account {} restored", restored.email());
        ledger.record(actorId, actorRole, LedgerAction.USER_RESTORED, restored.id().toString(),
                restored.email() + "|" + restored.role());
        return restored;
    }

    /**
     * Issues a new password, shown once.
     *
     * <p>Generated rather than chosen, for the same reason the initial one is, and returned in
     * the response and nowhere else. Sessions are ended: a reset is either a recovery, in which
     * case nobody is signed in, or a response to a password being in the wrong hands, in which
     * case whoever is signed in should stop being.
     *
     * @param targetId  whose password to replace
     * @param actorId   the administrator doing it
     * @param actorRole the role recorded in the ledger
     * @return the account and its new password
     * @throws NotFound when no such account exists
     */
    public PasswordReset resetPassword(UUID targetId, UUID actorId, String actorRole) {
        UserAccount target = require(targetId);

        String password = InitialPassword.generate();
        UserAccount updated = users.save(target.withPasswordHash(passwords.hash(password)));
        int ended = tokens.revokeSessionsFor(target.id());

        log.info("password reset for {}, {} session(s) ended", updated.email(), ended);
        // The password is not in the entry, the log or anywhere else. What is recorded is that
        // a reset happened, to whom, and by whom -- which is the auditable fact.
        ledger.record(actorId, actorRole, LedgerAction.USER_PASSWORD_RESET,
                updated.id().toString(), updated.email() + "|sessions=" + ended);

        return new PasswordReset(updated, password);
    }

    /**
     * Clears an account's second factor, so the next sign-in enrols a new one.
     *
     * <p>This is the answer to a lost phone. The stored secret is dropped rather than kept and
     * flagged: a retained secret is one somebody may still hold on a device that is no longer
     * in the owner's hands, which is the situation the reset exists to end.
     *
     * @param targetId  whose second factor to clear
     * @param actorId   the administrator doing it
     * @param actorRole the role recorded in the ledger
     * @return the account, now unenrolled
     * @throws Rejected when the account has no second factor to clear
     * @throws NotFound when no such account exists
     */
    public UserAccount resetSecondFactor(UUID targetId, UUID actorId, String actorRole) {
        UserAccount target = require(targetId);

        if (!target.mfaEnrolled() && target.mfaSecret() == null) {
            throw new Rejected(target.email() + " has no second factor enrolled. "
                    + (target.role().requiresMfa()
                            ? "One will be collected on their next sign-in."
                            : "This role is not asked for one."));
        }

        UserAccount cleared = users.save(target.withoutSecondFactor());
        int ended = tokens.revokeSessionsFor(target.id());

        log.info("second factor cleared for {}, {} session(s) ended", cleared.email(), ended);
        ledger.record(actorId, actorRole, LedgerAction.USER_MFA_RESET, cleared.id().toString(),
                cleared.email() + "|" + cleared.role() + "|sessions=" + ended);
        return cleared;
    }

    private UserAccount require(UUID targetId) {
        if (targetId == null) {
            throw new NotFound("An account must be named.");
        }
        return users.findById(targetId)
                .orElseThrow(() -> new NotFound("No account with id " + targetId + "."));
    }

    /**
     * Refuses to suspend the last administrator who could still sign in.
     *
     * <p>Counts the ones who could actually be used, not the ones that exist: an administrator
     * who is already suspended cannot rescue anybody.
     */
    private void requireAnotherAdministratorRemains(UserAccount target) {
        if (target.role() != Role.ADMIN) {
            return;
        }
        long remaining = users.findAll().stream()
                .filter(account -> account.role() == Role.ADMIN)
                .filter(account -> !account.disabled())
                .filter(account -> !account.id().equals(target.id()))
                .count();

        if (remaining == 0) {
            throw new Rejected("This is the only administrator who can still sign in. "
                    + "Suspending it would leave a system nobody can administer and nobody can "
                    + "create an administrator for. Create another administrator first.");
        }
    }
}
