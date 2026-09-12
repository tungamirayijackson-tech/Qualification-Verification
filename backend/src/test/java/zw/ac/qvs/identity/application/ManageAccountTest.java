package zw.ac.qvs.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * What an administrator can do to an account once it exists.
 *
 * <p>Three of these tests describe holes that were open until this class was written: nothing
 * could withdraw somebody's access, nothing could replace a lost password, and nothing could
 * clear a second factor whose device was gone. The rest describe the guards, and the guards are
 * the interesting part — an operation that withdraws access is one keystroke away from an
 * operation that locks everybody out.
 */
@Requirement("FR-10")
class ManageAccountTest {

    private static final UUID ADMIN = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID OTHER_ADMIN =
            UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID INSTITUTION =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    private Users users;
    private Sessions sessions;
    private InMemoryLedger ledger;
    private ManageAccount manageAccount;

    @BeforeEach
    void setUp() {
        users = new Users();
        sessions = new Sessions();
        ledger = new InMemoryLedger();
        manageAccount = new ManageAccount(users, new ReversibleHasher(), sessions,
                new AppendEntry(ledger, Clock.fixed(Instant.parse("2026-09-08T10:00:00Z"),
                        ZoneOffset.UTC)));

        // Two administrators, so the "last administrator" guard is not tripped by every test
        // that happens to touch one.
        users.add(account(ADMIN, "admin@qvs.ac.zw", Role.ADMIN, null));
        users.add(account(OTHER_ADMIN, "admin2@qvs.ac.zw", Role.ADMIN, null));
    }

    private static UserAccount account(UUID id, String email, Role role, UUID institution) {
        return new UserAccount(id, email, "A Person", role, institution, "hashed:their-password",
                null, false, false);
    }

    private UserAccount registrar() {
        UUID id = UUID.randomUUID();
        return users.add(account(id, "registrar@example.ac.zw", Role.REGISTRAR, INSTITUTION));
    }

    private UserAccount enrolledRegistrar() {
        UserAccount plain = registrar();
        return users.save(new UserAccount(plain.id(), plain.email(), plain.displayName(),
                plain.role(), plain.institutionId(), plain.passwordHash(), "ASECRET", true,
                false));
    }

    @Nested
    @DisplayName("suspending an account")
    class Suspending {

        @Test
        @DisplayName("stops it signing in, which nothing could do before")
        void suspends() {
            UserAccount target = registrar();

            UserAccount suspended = manageAccount.disable(target.id(), ADMIN, "ADMIN");

            assertThat(suspended.disabled()).isTrue();
            assertThat(suspended.canSignIn())
                    .as("the flag sign-in already checked now has something that sets it")
                    .isFalse();
            assertThat(users.rows.get(target.id()).disabled()).isTrue();
        }

        @Test
        @DisplayName("ends the sessions it holds, rather than leaving it signed in")
        void endsSessions() {
            UserAccount target = registrar();

            manageAccount.disable(target.id(), ADMIN, "ADMIN");

            assertThat(sessions.revokedFor).containsExactly(target.id());
        }

        @Test
        @DisplayName("keeps the account, because the ledger names it in what it did")
        void keepsTheRow() {
            UserAccount target = registrar();

            manageAccount.disable(target.id(), ADMIN, "ADMIN");

            assertThat(users.rows).containsKey(target.id());
        }

        @Test
        @DisplayName("is recorded against the administrator who did it")
        void isAudited() {
            UserAccount target = registrar();

            manageAccount.disable(target.id(), ADMIN, "ADMIN");

            var entries = ledger.withAction(LedgerAction.USER_DISABLED);
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).actorId()).isEqualTo(ADMIN);
            assertThat(entries.get(0).subjectRef()).isEqualTo(target.id().toString());
        }

        @Test
        @DisplayName("is refused on an account that is already suspended")
        void refusesTwice() {
            UserAccount target = registrar();
            manageAccount.disable(target.id(), ADMIN, "ADMIN");

            assertThatThrownBy(() -> manageAccount.disable(target.id(), ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.Rejected.class)
                    .hasMessageContaining("already suspended");

            assertThat(ledger.withAction(LedgerAction.USER_DISABLED))
                    .as("a refusal records nothing, so the ledger does not claim a second change")
                    .hasSize(1);
        }

        @Test
        @DisplayName("is refused on the administrator's own account")
        void refusesSelf() {
            // They could not undo it: undoing it requires an administrator.
            assertThatThrownBy(() -> manageAccount.disable(ADMIN, ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.Rejected.class)
                    .hasMessageContaining("cannot suspend their own account");
        }

        @Test
        @DisplayName("is refused for the last administrator who can still sign in")
        void refusesTheLastAdministrator() {
            // The one that would leave a system nobody can administer, and that nobody can
            // create an administrator for, because creating one requires an administrator.
            manageAccount.disable(OTHER_ADMIN, ADMIN, "ADMIN");

            assertThatThrownBy(() -> manageAccount.disable(ADMIN, OTHER_ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.Rejected.class)
                    .hasMessageContaining("only administrator");
        }

        @Test
        @DisplayName("counts administrators who could sign in, not ones that merely exist")
        void alreadySuspendedAdministratorsDoNotCount() {
            // A suspended administrator cannot rescue anybody, so it is not one that remains.
            manageAccount.disable(OTHER_ADMIN, ADMIN, "ADMIN");
            UserAccount third = users.add(
                    account(UUID.randomUUID(), "admin3@qvs.ac.zw", Role.ADMIN, null));

            assertThat(manageAccount.disable(third.id(), ADMIN, "ADMIN").disabled())
                    .as("a third enabled administrator exists, so this one may go")
                    .isTrue();
            assertThatThrownBy(() -> manageAccount.disable(ADMIN, third.id(), "ADMIN"))
                    .as("and now none does")
                    .isInstanceOf(ManageAccount.Rejected.class);
        }

        @Test
        @DisplayName("does not guard a registrar, however few of them there are")
        void guardsOnlyAdministrators() {
            UserAccount only = registrar();

            assertThat(manageAccount.disable(only.id(), ADMIN, "ADMIN").disabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("restoring an account")
    class Restoring {

        @Test
        @DisplayName("gives the access back")
        void restores() {
            UserAccount target = registrar();
            manageAccount.disable(target.id(), ADMIN, "ADMIN");

            UserAccount restored = manageAccount.restore(target.id(), ADMIN, "ADMIN");

            assertThat(restored.disabled()).isFalse();
        }

        @Test
        @DisplayName("leaves the password alone, so a return from leave is not a lockout")
        void keepsThePassword() {
            UserAccount target = registrar();
            manageAccount.disable(target.id(), ADMIN, "ADMIN");

            UserAccount restored = manageAccount.restore(target.id(), ADMIN, "ADMIN");

            assertThat(restored.passwordHash()).isEqualTo(target.passwordHash());
        }

        @Test
        @DisplayName("is refused on an account that was not suspended")
        void refusesWhenNotSuspended() {
            UserAccount target = registrar();

            assertThatThrownBy(() -> manageAccount.restore(target.id(), ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.Rejected.class)
                    .hasMessageContaining("not suspended");
        }

        @Test
        @DisplayName("is recorded")
        void isAudited() {
            UserAccount target = registrar();
            manageAccount.disable(target.id(), ADMIN, "ADMIN");

            manageAccount.restore(target.id(), ADMIN, "ADMIN");

            assertThat(ledger.withAction(LedgerAction.USER_RESTORED)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("resetting a password")
    class ResettingAPassword {

        @Test
        @DisplayName("hands back a new one, once")
        void issuesANewPassword() {
            UserAccount target = registrar();

            var reset = manageAccount.resetPassword(target.id(), ADMIN, "ADMIN");

            assertThat(reset.newPassword()).isNotBlank();
            assertThat(reset.newPassword()).hasSizeGreaterThanOrEqualTo(20);
        }

        @Test
        @DisplayName("stores only the hash of it")
        void storesOnlyTheHash() {
            UserAccount target = registrar();

            var reset = manageAccount.resetPassword(target.id(), ADMIN, "ADMIN");

            assertThat(users.rows.get(target.id()).passwordHash())
                    .isEqualTo("hashed:" + reset.newPassword())
                    .isNotEqualTo(reset.newPassword());
        }

        @Test
        @DisplayName("replaces the old one, so the lost password is dead")
        void replacesTheOldOne() {
            UserAccount target = registrar();
            String before = target.passwordHash();

            manageAccount.resetPassword(target.id(), ADMIN, "ADMIN");

            assertThat(users.rows.get(target.id()).passwordHash()).isNotEqualTo(before);
        }

        @Test
        @DisplayName("gives a different password every time")
        void isNotPredictable() {
            UserAccount target = registrar();

            var first = manageAccount.resetPassword(target.id(), ADMIN, "ADMIN");
            var second = manageAccount.resetPassword(target.id(), ADMIN, "ADMIN");

            assertThat(first.newPassword()).isNotEqualTo(second.newPassword());
        }

        @Test
        @DisplayName("ends the sessions, because a reset answers a password in the wrong hands")
        void endsSessions() {
            UserAccount target = registrar();

            manageAccount.resetPassword(target.id(), ADMIN, "ADMIN");

            assertThat(sessions.revokedFor).containsExactly(target.id());
        }

        @Test
        @DisplayName("is recorded, and the password is not in the entry")
        void isAuditedWithoutThePassword() {
            UserAccount target = registrar();

            var reset = manageAccount.resetPassword(target.id(), ADMIN, "ADMIN");

            var entries = ledger.withAction(LedgerAction.USER_PASSWORD_RESET);
            assertThat(entries).hasSize(1);
            // The ledger is readable by every auditor and exportable to CSV. A credential in it
            // would make the audit trail a more attractive target than the account it describes.
            assertThat(entries.get(0).subjectRef()).doesNotContain(reset.newPassword());
            assertThat(ledger.entries().toString()).doesNotContain(reset.newPassword());
        }

        @Test
        @DisplayName("works on a suspended account, which is how a return is prepared")
        void worksOnASuspendedAccount() {
            UserAccount target = registrar();
            manageAccount.disable(target.id(), ADMIN, "ADMIN");

            var reset = manageAccount.resetPassword(target.id(), ADMIN, "ADMIN");

            assertThat(reset.account().disabled())
                    .as("and does not quietly restore it -- that is a separate decision")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("resetting a second factor")
    class ResettingASecondFactor {

        @Test
        @DisplayName("clears it, so the next sign-in enrols a new one")
        void clearsIt() {
            UserAccount target = enrolledRegistrar();

            UserAccount cleared = manageAccount.resetSecondFactor(target.id(), ADMIN, "ADMIN");

            assertThat(cleared.mfaEnrolled()).isFalse();
            assertThat(cleared.requiresSecondFactor()).isFalse();
        }

        @Test
        @DisplayName("drops the secret rather than keeping it")
        void dropsTheSecret() {
            // A retained secret is one somebody may still hold on the device that was lost,
            // which is the situation this exists to end.
            UserAccount target = enrolledRegistrar();

            UserAccount cleared = manageAccount.resetSecondFactor(target.id(), ADMIN, "ADMIN");

            assertThat(cleared.mfaSecret()).isNull();
            assertThat(users.rows.get(target.id()).mfaSecret()).isNull();
        }

        @Test
        @DisplayName("ends the sessions signed in with the old factor")
        void endsSessions() {
            UserAccount target = enrolledRegistrar();

            manageAccount.resetSecondFactor(target.id(), ADMIN, "ADMIN");

            assertThat(sessions.revokedFor).containsExactly(target.id());
        }

        @Test
        @DisplayName("is refused when there is nothing enrolled to clear")
        void refusesWhenNothingEnrolled() {
            UserAccount target = registrar();

            assertThatThrownBy(() -> manageAccount.resetSecondFactor(target.id(), ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.Rejected.class)
                    .hasMessageContaining("no second factor");
        }

        @Test
        @DisplayName("is recorded, because it briefly lowers the account to one factor")
        void isAudited() {
            UserAccount target = enrolledRegistrar();

            manageAccount.resetSecondFactor(target.id(), ADMIN, "ADMIN");

            assertThat(ledger.withAction(LedgerAction.USER_MFA_RESET)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("an account that is not there")
    class Missing {

        @Test
        @DisplayName("is a 404's worth of not-found, not a refusal")
        void notFound() {
            UUID nobody = UUID.randomUUID();

            assertThatThrownBy(() -> manageAccount.disable(nobody, ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.NotFound.class);
            assertThatThrownBy(() -> manageAccount.resetPassword(nobody, ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.NotFound.class);
            assertThatThrownBy(() -> manageAccount.resetSecondFactor(nobody, ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.NotFound.class);
        }

        @Test
        @DisplayName("and nothing is recorded about it")
        void recordsNothing() {
            assertThatThrownBy(() -> manageAccount.disable(UUID.randomUUID(), ADMIN, "ADMIN"))
                    .isInstanceOf(ManageAccount.NotFound.class);

            assertThat(ledger.entries()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ stubs

    private static final class Users implements UserRepository {
        private final Map<UUID, UserAccount> rows = new HashMap<>();

        UserAccount add(UserAccount account) {
            return save(account);
        }

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
            throw new UnsupportedOperationException("managing an account does not count them");
        }

        @Override
        public UserAccount save(UserAccount account) {
            rows.put(account.id(), account);
            return account;
        }
    }

    /** Records whose sessions were ended, which is the observable part of a revocation. */
    private static final class Sessions implements TokenIssuer {
        private final List<UUID> revokedFor = new ArrayList<>();

        @Override
        public Tokens issue(UserAccount account) {
            throw new UnsupportedOperationException("managing an account issues no tokens");
        }

        @Override
        public Tokens refresh(String refreshToken) {
            throw new UnsupportedOperationException("managing an account issues no tokens");
        }

        @Override
        public int revokeSessionsFor(UUID userId) {
            revokedFor.add(userId);
            return 1;
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
