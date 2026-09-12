package zw.ac.qvs.identity.application;

import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;

/**
 * Creates an account, which is how every other power in this system is handed out.
 *
 * <p>A registrar can sign credentials on an institution's behalf; an administrator can admit
 * institutions and rotate the keys everything else is verified against. So this is audited, and
 * the entry names both the account created and the administrator who created it — "who let them
 * in" is exactly the question the ledger exists to answer.
 *
 * <h2>The initial password is generated, not chosen</h2>
 *
 * <p>It is returned <b>once</b>, in the response, and stored only as a bcrypt hash — the same
 * shape as a share token, and for the same reason. An administrator typing an initial password
 * picks a memorable one, and a memorable password handed over in an email is the weakest link in
 * an otherwise carefully built chain. Generating it removes the choice; returning it once means
 * the system cannot show it again, so it has to be passed on deliberately.
 *
 * <h2>Role and institution are not independent</h2>
 *
 * <p>A registrar acts for exactly one institution and must be given one, and that institution
 * must already be in the register — an administrator admits the institution first, then creates
 * the people who act for it. An auditor is deliberately cross-institution and must not be given
 * one at all. Every one of those rules is also enforced by the database, as a CHECK and a
 * foreign key; they are restated here so the caller gets a sentence explaining the refusal
 * rather than a constraint violation, which reaches an administrator as a failure with no
 * information in it.
 */
public class CreateUser {

    private static final Logger log = LoggerFactory.getLogger(CreateUser.class);

    private final UserRepository users;
    private final PasswordHasher passwords;
    private final KnownInstitutions institutions;
    private final AppendEntry ledger;

    public CreateUser(UserRepository users, PasswordHasher passwords,
            KnownInstitutions institutions, AppendEntry ledger) {
        this.users = users;
        this.passwords = passwords;
        this.institutions = institutions;
        this.ledger = ledger;
    }

    /** Refused for a reason the caller can act on. */
    public static class Rejected extends RuntimeException {
        public Rejected(String message) {
            super(message);
        }
    }

    /**
     * What an administrator supplies.
     *
     * @param email         the address they will sign in with
     * @param displayName   how they are named on screen and in the ledger
     * @param role          what they may do
     * @param institutionId the institution a registrar acts for; null for every other role
     */
    public record Command(String email, String displayName, Role role, UUID institutionId) {
    }

    /**
     * The account, and the one and only sight of its password.
     *
     * @param account         the stored account
     * @param initialPassword the generated password, which this system will never show again
     */
    public record Created(UserAccount account, String initialPassword) {
    }

    /**
     * Creates an account.
     *
     * @param command what to create
     * @param actorId the administrator doing it, or null for the first-run bootstrap
     * @param actorRole the role recorded in the ledger
     * @return the account and its initial password
     * @throws Rejected when the email is taken, or the role and institution do not agree
     */
    public Created create(Command command, UUID actorId, String actorRole) {
        return create(command, actorId, actorRole, InitialPassword.generate());
    }

    /**
     * Creates an account with a password the caller already holds.
     *
     * <p>Exists for exactly one caller: the first-run bootstrap, where the operator sets the
     * password in the environment and has to be able to sign in with what they set. Everywhere
     * else the password is generated, and the endpoint deliberately offers no way to supply one
     * — an administrator choosing an initial password picks a memorable one, and a memorable
     * password sent in an email is the weakest link in an otherwise careful chain.
     *
     * @param command    what to create
     * @param actorId    who is doing it, or null for the bootstrap
     * @param actorRole  the role recorded in the ledger
     * @param password   the password to set
     * @return the account, and the password it was given
     */
    public Created create(Command command, UUID actorId, String actorRole, String password) {
        String email = normalisedEmail(command.email());

        if (command.role() == null) {
            throw new Rejected("A role is required: REGISTRAR, VERIFIER, AUDITOR or ADMIN.");
        }
        if (command.displayName() == null || command.displayName().isBlank()) {
            throw new Rejected("A display name is required.");
        }

        users.findByEmail(email).ifPresent(existing -> {
            throw new Rejected("An account already exists for " + email + ".");
        });

        UUID institutionId = requireInstitutionToMatchRole(command);
        requireInstitutionExists(institutionId);

        UserAccount account = new UserAccount(
                UUID.randomUUID(),
                email,
                command.displayName().trim(),
                command.role(),
                institutionId,
                passwords.hash(password),
                // No MFA secret and not enrolled. A role that requires a second factor collects
                // it on first sign-in, from the enrolment flow, on the new user's own device --
                // which is the only place a second factor is worth anything.
                null,
                false,
                false);

        UserAccount stored = users.save(account);

        log.info("account created for {} as {}", stored.email(), stored.role());
        ledger.record(actorId, actorRole, LedgerAction.USER_CREATED, stored.id().toString(),
                stored.email() + "|" + stored.role()
                        + (institutionId == null ? "" : "|institution=" + institutionId));

        return new Created(stored, password);
    }

    /**
     * Checks the role against the institution it was given.
     *
     * @return the institution to store, which is null for every role but registrar
     */
    private static UUID requireInstitutionToMatchRole(Command command) {
        if (command.role().isInstitutionScoped()) {
            if (command.institutionId() == null) {
                throw new Rejected("A registrar acts for exactly one institution, so one must be "
                        + "named. Admit the institution first if it is not in the register yet.");
            }
            return command.institutionId();
        }

        if (command.institutionId() != null) {
            // Refused rather than ignored. Silently dropping it would leave the administrator
            // believing they had scoped an auditor to one institution, which is the opposite of
            // what an auditor is for.
            throw new Rejected("An " + command.role() + " is not bound to one institution, so "
                    + "none may be named. Auditors read across all of them by design.");
        }
        return null;
    }

    /**
     * Refuses an institution that has not been admitted yet.
     *
     * <p>The foreign key would refuse it too, a moment later and without a sentence. This is
     * also the order the system is meant to be used in: admit the institution, issue it a
     * signing key, then create the registrar who will sign on its behalf.
     */
    private void requireInstitutionExists(UUID institutionId) {
        if (institutionId != null && !institutions.exists(institutionId)) {
            throw new Rejected("No institution with id " + institutionId + " is in the register. "
                    + "Admit the institution first, then create the people who act for it.");
        }
    }

    private static String normalisedEmail(String email) {
        if (email == null || !email.contains("@")) {
            throw new Rejected("A valid email address is required.");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
