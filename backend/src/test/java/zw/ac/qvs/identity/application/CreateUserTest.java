package zw.ac.qvs.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.testsupport.InMemoryLedger;
import zw.ac.qvs.testsupport.Requirement;

/**
 * Creating an account — the act that hands out every other power in the system.
 *
 * <p>The rules worth pinning down are the refusals and the password. A registrar created here
 * can sign credentials on an institution's behalf, so who may be created, bound to what, and
 * with what secret are not incidental details.
 */
@Requirement("FR-10")
class CreateUserTest {

    private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID INSTITUTION = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private Users users;
    private InMemoryLedger ledger;
    private CreateUser createUser;

    /** The institutions an administrator has already admitted. */
    private Set<UUID> admitted;

    @BeforeEach
    void setUp() {
        users = new Users();
        ledger = new InMemoryLedger();
        admitted = new HashSet<>(List.of(INSTITUTION));
        createUser = new CreateUser(users, new ReversibleHasher(), admitted::contains,
                new AppendEntry(ledger, Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"),
                        ZoneOffset.UTC)));
    }

    private CreateUser.Created create(String email, Role role, UUID institution) {
        return createUser.create(
                new CreateUser.Command(email, "A Person", role, institution), ADMIN, "ADMIN");
    }

    @Nested
    @DisplayName("the account")
    class Account {

        @Test
        @DisplayName("is stored, with the email folded to lower case")
        void isStored() {
            var created = create("Tendai.Moyo@Example.AC.ZW", Role.AUDITOR, null);

            assertThat(created.account().email()).isEqualTo("tendai.moyo@example.ac.zw");
            assertThat(users.rows).containsKey(created.account().id());
        }

        @Test
        @DisplayName("starts without a second factor, so the new user enrols on their own device")
        void startsUnenrolled() {
            // The point of a second factor is that the person holds it. Pre-enrolling one here
            // would mean the administrator held it first, which is not a second factor at all.
            var created = create("admin2@qvs.ac.zw", Role.ADMIN, null);

            assertThat(created.account().mfaEnrolled()).isFalse();
            assertThat(created.account().mfaSecret()).isNull();
        }

        @Test
        @DisplayName("is recorded in the ledger, naming the administrator who created it")
        void isAudited() {
            var created = create("auditor2@qvs.ac.zw", Role.AUDITOR, null);

            assertThat(ledger.withAction(LedgerAction.USER_CREATED)).singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.subjectRef()).isEqualTo(created.account().id().toString());
                        assertThat(entry.actorId()).isEqualTo(ADMIN);
                    });
        }
    }

    @Nested
    @DisplayName("the initial password")
    class InitialPassword {

        @Test
        @DisplayName("is generated, not chosen, and returned once")
        void isGenerated() {
            var created = create("someone@qvs.ac.zw", Role.AUDITOR, null);

            assertThat(created.initialPassword()).isNotBlank().hasSizeGreaterThanOrEqualTo(20);
        }

        @Test
        @DisplayName("is never the same twice")
        void isUnique() {
            List<String> issued = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                issued.add(create("person" + i + "@qvs.ac.zw", Role.AUDITOR, null)
                        .initialPassword());
            }

            assertThat(issued).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("is stored only as a hash, never in the clear")
        void isStoredHashed() {
            var created = create("someone@qvs.ac.zw", Role.AUDITOR, null);

            assertThat(created.account().passwordHash())
                    .as("the stored value must not be the password itself")
                    .isNotEqualTo(created.initialPassword());
        }

        @Test
        @DisplayName("never reaches the audit ledger")
        void isNotAudited() {
            // The ledger outlives the account and is read by auditors. A credential in it would
            // be a credential in every export of it.
            var created = create("someone@qvs.ac.zw", Role.AUDITOR, null);

            assertThat(ledger.entries())
                    .noneMatch(entry -> entry.subjectRef().contains(created.initialPassword()));
        }
    }

    @Nested
    @DisplayName("role and institution have to agree")
    class Scoping {

        @Test
        @DisplayName("a registrar must be given an institution")
        void registrarNeedsOne() {
            assertThatThrownBy(() -> create("registrar@example.ac.zw", Role.REGISTRAR, null))
                    .isInstanceOf(CreateUser.Rejected.class)
                    .hasMessageContaining("exactly one institution");
        }

        @Test
        @DisplayName("and keeps it")
        void registrarKeepsIt() {
            var created = create("registrar@example.ac.zw", Role.REGISTRAR, INSTITUTION);

            assertThat(created.account().institutionId()).isEqualTo(INSTITUTION);
        }

        @Test
        @DisplayName("must already be in the register, because the institution is admitted first")
        void institutionMustExist() {
            // The order the system is meant to be used in: admit the institution, give it a
            // signing key, then create the registrar who signs on its behalf. The foreign key
            // refuses this too, a moment later and without a sentence in it.
            UUID neverAdmitted = UUID.randomUUID();

            assertThatThrownBy(
                    () -> create("registrar@nowhere.ac.zw", Role.REGISTRAR, neverAdmitted))
                    .isInstanceOf(CreateUser.Rejected.class)
                    .hasMessageContaining("Admit the institution first");

            assertThat(users.rows).isEmpty();
        }

        @Test
        @DisplayName("an auditor may not be given one, and is refused rather than corrected")
        void auditorMustNotHaveOne() {
            // Silently dropping it would leave the administrator believing they had scoped an
            // auditor to one institution, which is the opposite of what an auditor is for.
            assertThatThrownBy(() -> create("auditor@qvs.ac.zw", Role.AUDITOR, INSTITUTION))
                    .isInstanceOf(CreateUser.Rejected.class)
                    .hasMessageContaining("not bound to one institution");
        }

        @Test
        @DisplayName("nor may an administrator")
        void adminMustNotHaveOne() {
            assertThatThrownBy(() -> create("admin@qvs.ac.zw", Role.ADMIN, INSTITUTION))
                    .isInstanceOf(CreateUser.Rejected.class);
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an address that already has an account")
        void duplicateEmail() {
            create("taken@qvs.ac.zw", Role.AUDITOR, null);

            assertThatThrownBy(() -> create("TAKEN@qvs.ac.zw", Role.AUDITOR, null))
                    .as("compared after folding, so case cannot be used to make a second account")
                    .isInstanceOf(CreateUser.Rejected.class)
                    .hasMessageContaining("already exists");
        }

        @Test
        @DisplayName("something that is not an address")
        void notAnEmail() {
            assertThatThrownBy(() -> create("not-an-address", Role.AUDITOR, null))
                    .isInstanceOf(CreateUser.Rejected.class);
        }

        @Test
        @DisplayName("and a refusal creates nothing and records nothing")
        void refusalsLeaveNoTrace() {
            create("taken@qvs.ac.zw", Role.AUDITOR, null);
            int after = users.rows.size();

            assertThatThrownBy(() -> create("taken@qvs.ac.zw", Role.AUDITOR, null))
                    .isInstanceOf(CreateUser.Rejected.class);

            assertThat(users.rows).hasSize(after);
            assertThat(ledger.withAction(LedgerAction.USER_CREATED)).hasSize(1);
        }
    }

    // ------------------------------------------------------------------ stubs

    private static final class Users implements UserRepository {
        private final Map<UUID, UserAccount> rows = new HashMap<>();

        @Override
        public Optional<UserAccount> findByEmail(String email) {
            return rows.values().stream().filter(row -> row.email().equals(email)).findFirst();
        }

        @Override
        public Optional<UserAccount> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<UserAccount> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public UserAccount save(UserAccount account) {
            rows.put(account.id(), account);
            return account;
        }
    }

    /**
     * A hasher that is obviously not one, so a test asserting "the stored value is not the
     * password" is asserting something rather than relying on bcrypt to be slow.
     */
    private static final class ReversibleHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String storedHash) {
            return hash(rawPassword).equals(storedHash);
        }
    }
}
