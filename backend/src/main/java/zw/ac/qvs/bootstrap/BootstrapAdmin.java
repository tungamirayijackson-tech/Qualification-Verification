package zw.ac.qvs.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import zw.ac.qvs.identity.application.CreateUser;
import zw.ac.qvs.identity.application.UserRepository;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.shared.adapter.QvsProperties;

/**
 * The first administrator, so a cold system can be started at all.
 *
 * <p>Until this existed there was no way to create an account outside the {@code dev} seeder,
 * which means a deployment on the {@code prod} profile came up with an empty user table and no
 * route to a first sign-in. Every other power in this system is handed out by an administrator;
 * without one, nothing can be handed out.
 *
 * <h2>Why configuration rather than a setup endpoint</h2>
 *
 * <p>The obvious alternative is an unauthenticated {@code POST /setup} that works while the
 * register is empty. It is also a new unauthenticated write endpoint on a system whose whole
 * shape is built around having exactly one — and one whose guard ("is the table empty?") is a
 * race in a scaled-out deployment, where two instances can both find it empty. Configuration has
 * neither problem: the credentials come from the environment, the same place every other secret
 * here comes from, and the operator who can set them already owns the deployment.
 *
 * <h2>It runs once, and declines to run again</h2>
 *
 * <p>The condition is "the register holds no accounts at all", not "there is no administrator".
 * That is deliberate: it means this can never be used to add a second administrator to a running
 * system, which is what it would become if the check were narrower. Once anybody exists, this
 * says nothing and does nothing.
 *
 * <p>An empty register does mean a restart would create the administrator again, and that is the
 * intended behaviour rather than an oversight — it is the recovery path when a deployment is
 * rebuilt from an empty database, which NFR-05 requires to work. The act is recorded in the
 * audit ledger either way, so an administrator appearing is never silent.
 */
@Component
// After Flyway and after any dev seeding, so "is the register empty" is asked of the finished
// schema rather than of one still being migrated.
@Order(100)
public class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    /** Short enough to type, long enough that it is not the weak link. */
    private static final int MINIMUM_PASSWORD_LENGTH = 12;

    private final UserRepository users;
    private final CreateUser createUser;
    private final TransactionTemplate transactions;
    private final QvsProperties.Bootstrap configured;

    public BootstrapAdmin(
            UserRepository users,
            CreateUser createUser,
            TransactionTemplate transactions,
            QvsProperties properties) {
        this.users = users;
        this.createUser = createUser;
        this.transactions = transactions;
        this.configured = properties.bootstrap();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }

        if (!configured.isComplete()) {
            // Loud, because the system is unusable in this state and the reason is not
            // discoverable from the sign-in screen: it will simply refuse every credential.
            log.warn("""
                    No accounts exist and no bootstrap administrator is configured, so nobody \
                    can sign in. Set QVS_BOOTSTRAP_ADMIN_EMAIL and QVS_BOOTSTRAP_ADMIN_PASSWORD \
                    and restart. The password is used once, to create the account; it is stored \
                    only as a hash.""");
            return;
        }

        if (configured.adminPassword().length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "The bootstrap administrator's password must be at least "
                            + MINIMUM_PASSWORD_LENGTH + " characters. Refusing to create the "
                            + "account that every other account will be created by with a "
                            + "password shorter than that.");
        }

        // The ledger append requires a transaction: the chain is written under
        // Propagation.MANDATORY so that forgetting one fails loudly rather than recording
        // nothing.
        var created = transactions.execute(status -> createUser.create(
                new CreateUser.Command(
                        configured.adminEmail(), "Bootstrap Administrator", Role.ADMIN, null),
                // No actor: nobody authorised this but the operator holding the environment.
                null,
                "SYSTEM",
                // The configured password, not a generated one. This is the one account whose
                // password the caller must already know -- there is nobody to hand a generated
                // one to, and an account created with a password nobody holds is an account
                // nobody can use.
                configured.adminPassword()));

        // The configured password is deliberately not echoed -- the operator set it and a
        // credential in a log file outlives the reason it was written there.
        log.warn("""
                Created the first administrator, {}, from configuration. Sign in with the \
                password you configured; a second factor is collected on first sign-in. This \
                runs only while the register holds no accounts, so it will not run again.""",
                created.account().email());
    }
}
