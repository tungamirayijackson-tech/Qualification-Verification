package zw.ac.qvs.bootstrap;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import zw.ac.qvs.credential.application.RegisterCredential;
import zw.ac.qvs.credential.application.RegistrationRejected;
import zw.ac.qvs.identity.application.PasswordHasher;
import zw.ac.qvs.identity.application.UserRepository;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.verification.application.KeyVault;

/**
 * Makes the {@code dev} profile a system somebody can actually demonstrate.
 *
 * <p><b>Why this lives in {@code bootstrap} and not in {@code shared}.</b> Seeding needs the
 * credential, identity and verification modules all at once. Putting it in {@code shared} made
 * shared depend on credential, and since credential already depends on ledger and ledger on
 * shared, that closed a cycle — which the ArchUnit rule caught. {@code bootstrap} is a
 * composition root: it may depend on every module, and no module depends on it, so it can
 * never take part in a cycle.
 *
 * <p>Two things here cannot be done in SQL and so cannot live in the Flyway seed. A signing key
 * has a private half that must be generated and written to the vault in the same operation
 * that records the public half, or the register ends up referencing a key nothing can sign
 * with. And a credential must be genuinely signed at issuance — inserting a row with a
 * hand-written signature column would produce a credential that fails verification, which is
 * the opposite of a useful demonstration.
 *
 * <p>Everything created here is idempotent, so restarting the stack does not accumulate
 * duplicates or fail.
 *
 * <p><b>The passwords below are deliberately weak and deliberately public.</b> They exist so an
 * assessor can sign in, and they are confined to a profile that is never active in production.
 * A seeded account with a real-looking password is worse than an obviously fake one, because
 * somebody eventually copies it somewhere that matters.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final UUID EXAMPLE_UNIVERSITY =
            UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CAPE_INSTITUTE =
            UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BSC_COMPUTER_SCIENCE =
            UUID.fromString("aaaaaaaa-0001-4111-8111-aaaaaaaaaaaa");
    private static final UUID MSC_INFORMATION_SYSTEMS =
            UUID.fromString("aaaaaaaa-0002-4111-8111-aaaaaaaaaaaa");

    private static final String DEMO_PASSWORD = "demo-password-not-for-production";

    private final JdbcTemplate jdbc;
    private final KeyVault keyVault;
    private final UserRepository users;
    private final PasswordHasher passwords;
    private final RegisterCredential registerCredential;
    private final Clock clock;

    public DevDataSeeder(
            JdbcTemplate jdbc,
            KeyVault keyVault,
            UserRepository users,
            PasswordHasher passwords,
            RegisterCredential registerCredential,
            Clock clock) {
        this.jdbc = jdbc;
        this.keyVault = keyVault;
        this.users = users;
        this.passwords = passwords;
        this.registerCredential = registerCredential;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedKeys();
        UUID registrarId = seedUsers();
        seedCredentials(registrarId);
        log.info("dev seed complete: sign in as registrar@example.ac.zw / {}", DEMO_PASSWORD);
    }

    /** Generates a signing key per accredited institution, and points the register at it. */
    private void seedKeys() {
        for (UUID institution : List.of(EXAMPLE_UNIVERSITY, CAPE_INSTITUTE)) {
            if (keyVault.currentKeyFor(institution).isPresent()) {
                continue;
            }
            // Backdated so that awards conferred in earlier years still resolve a key that was
            // valid on their award date -- the situation Decision 05-A exists to handle.
            var key = keyVault.rotate(institution, LocalDate.now(clock).minusYears(6));
            jdbc.update("UPDATE institution SET active_key_id = ? WHERE id = ?",
                    key.kid(), institution);
            log.info("seeded signing key {} for institution {}", key.kid(), institution);
        }
    }

    /** One account per role, so the whole role matrix can be exercised by hand. */
    private UUID seedUsers() {
        UUID registrarId = ensureUser("registrar@example.ac.zw", "Tendai Moyo",
                Role.REGISTRAR, EXAMPLE_UNIVERSITY);
        ensureUser("registrar@hit.ac.zw", "Farai Ncube", Role.REGISTRAR, CAPE_INSTITUTE);
        ensureUser("auditor@qvs.ac.zw", "Rutendo Chikwanha", Role.AUDITOR, null);
        ensureUser("admin@qvs.ac.zw", "System Administrator", Role.ADMIN, null);
        return registrarId;
    }

    private UUID ensureUser(String email, String displayName, Role role, UUID institutionId) {
        return users.findByEmail(email)
                .map(UserAccount::id)
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    // MFA is marked enrolled with no secret so the demo can sign in without an
                    // authenticator app. The production path cannot reach this state: the
                    // enrolment flow always writes a secret before setting the flag.
                    users.save(new UserAccount(id, email, displayName, role, institutionId,
                            passwords.hash(DEMO_PASSWORD), null, false, false));
                    log.info("seeded {} account {}", role, email);
                    return id;
                });
    }

    /** A handful of genuinely signed credentials, including one that will be revoked. */
    private void seedCredentials(UUID registrarId) {
        Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM credential", Integer.class);
        if (existing != null && existing > 0) {
            return;
        }

        record Graduate(String nationalId, String name, UUID qualification, LocalDate awarded) {
        }

        List<Graduate> cohort = List.of(
                new Graduate("63-1234567K42", "Thandeka N. Mahlangu",
                        BSC_COMPUTER_SCIENCE, LocalDate.of(2026, 4, 11)),
                new Graduate("08-2345678M17", "Sibusiso Ndlovu",
                        BSC_COMPUTER_SCIENCE, LocalDate.of(2026, 4, 11)),
                new Graduate("25-3456789P08", "Renee Botha",
                        MSC_INFORMATION_SYSTEMS, LocalDate.of(2025, 12, 5)),
                new Graduate("12-5678901T26", "Aisha Patel",
                        BSC_COMPUTER_SCIENCE, LocalDate.of(2024, 4, 9)));

        for (Graduate graduate : cohort) {
            try {
                var credential = registerCredential.register(new RegisterCredential.Command(
                        EXAMPLE_UNIVERSITY, graduate.qualification(), graduate.nationalId(),
                        graduate.name(), null, graduate.awarded(), registrarId));
                log.info("seeded credential {} for {}", credential.serial(), graduate.name());
            } catch (RegistrationRejected e) {
                log.warn("dev seed could not register {}: {}", graduate.name(), e.getMessage());
            }
        }
    }
}
