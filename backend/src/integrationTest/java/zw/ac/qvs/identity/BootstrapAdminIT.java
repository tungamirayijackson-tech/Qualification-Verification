package zw.ac.qvs.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import zw.ac.qvs.bootstrap.BootstrapAdmin;
import zw.ac.qvs.identity.application.PasswordHasher;
import zw.ac.qvs.identity.application.UserRepository;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The first administrator, and the register that has nobody in it.
 *
 * <p>This is the entry point to the whole system: every other account is created by an
 * administrator, and until this existed there was no way to create the first one outside the
 * {@code dev} seeder. A deployment on {@code prod} came up with an empty user table and no route
 * to a first sign-in.
 *
 * <p>The property worth testing hardest is the second one below — that it declines to run when
 * anybody already exists. A bootstrap that fires more than once is not a bootstrap, it is a
 * standing back door into a running system.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "qvs.bootstrap.admin-email=first.admin@qvs.ac.zw",
        "qvs.bootstrap.admin-password=a-long-enough-bootstrap-password"
})
@Requirement("FR-10")
class BootstrapAdminIT extends PostgresIntegrationTest {

    private static final ApplicationArguments NO_ARGUMENTS = new DefaultApplicationArguments();

    @Autowired
    private BootstrapAdmin bootstrapAdmin;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordHasher passwords;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTheRegister() {
        // A cold system. The other integration tests seed their own accounts, so this starts by
        // removing whatever they left -- which is also the state this class exists to describe.
        jdbc.execute("TRUNCATE refresh_token, app_user CASCADE");
    }

    @Test
    @DisplayName("creates an administrator when the register holds nobody at all")
    void createsTheFirstAdministrator() {
        assertThat(users.count()).isZero();

        bootstrapAdmin.run(NO_ARGUMENTS);

        var admin = users.findByEmail("first.admin@qvs.ac.zw").orElseThrow();
        assertThat(admin.role()).isEqualTo(Role.ADMIN);
        assertThat(admin.institutionId())
                .as("an administrator is not bound to one institution")
                .isNull();
        assertThat(admin.disabled()).isFalse();
    }

    @Test
    @DisplayName("declines to run again once anybody exists, so it is not a standing back door")
    void doesNotRunTwice() {
        bootstrapAdmin.run(NO_ARGUMENTS);
        long afterFirst = users.count();

        bootstrapAdmin.run(NO_ARGUMENTS);
        bootstrapAdmin.run(NO_ARGUMENTS);

        assertThat(users.count())
                .as("the condition is 'the register is empty', not 'there is no administrator'")
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("declines when the register holds somebody who is not an administrator")
    void declinesWhenAnyoneExists() {
        // The narrower check -- "no admin exists" -- would let this add an administrator to a
        // system that already had users, which is exactly what it must never do.
        jdbc.update("""
                INSERT INTO app_user (id, email, display_name, role, password_hash)
                VALUES (?, 'someone@qvs.ac.zw', 'Someone', 'AUDITOR', 'hash')
                """, UUID.randomUUID());

        bootstrapAdmin.run(NO_ARGUMENTS);

        assertThat(users.findByEmail("first.admin@qvs.ac.zw")).isEmpty();
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the configured password is what signs in, and is stored only as a hash")
    void passwordIsUsableAndHashed() {
        bootstrapAdmin.run(NO_ARGUMENTS);

        var admin = users.findByEmail("first.admin@qvs.ac.zw").orElseThrow();

        assertThat(passwords.matches("a-long-enough-bootstrap-password", admin.passwordHash()))
                .as("the operator can actually sign in with what they configured")
                .isTrue();
        assertThat(admin.passwordHash())
                .as("and the password itself is not what was stored")
                .isNotEqualTo("a-long-enough-bootstrap-password");
    }

    @Test
    @DisplayName("arrives without a second factor, so ADMIN's MFA is collected on first sign-in")
    void arrivesUnenrolled() {
        // ADMIN requires MFA. Pre-enrolling one would mean the operator's environment held the
        // second factor, which is not a second factor. The 428 enrolment flow handles it.
        bootstrapAdmin.run(NO_ARGUMENTS);

        var admin = users.findByEmail("first.admin@qvs.ac.zw").orElseThrow();
        assertThat(admin.mfaEnrolled()).isFalse();
        assertThat(admin.mfaSecret()).isNull();
    }

    @Test
    @DisplayName("the account's creation is in the audit ledger")
    void isAudited() {
        Long before = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'USER_CREATED'", Long.class);

        bootstrapAdmin.run(NO_ARGUMENTS);

        Long after = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'USER_CREATED'", Long.class);

        // An administrator appearing is never silent, even when the system created it.
        assertThat(after - before).isEqualTo(1);

        String role = jdbc.queryForObject("""
                SELECT actor_role FROM audit_entry
                WHERE action = 'USER_CREATED' ORDER BY seq DESC LIMIT 1
                """, String.class);
        assertThat(role)
                .as("nobody authorised this but the operator holding the environment")
                .isEqualTo("SYSTEM");
    }
}
