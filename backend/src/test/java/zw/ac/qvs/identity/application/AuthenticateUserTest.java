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
import zw.ac.qvs.identity.domain.Totp;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.application.LedgerRepository;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.Chain;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.testsupport.Requirement;

/**
 * Signing in (FR-10).
 *
 * <p>The tests that matter most here are the ones asserting what the caller is <em>not</em>
 * told. A login form that distinguishes "no such account" from "wrong password" is a directory
 * of who holds an account, and it is a distinction that is very easy to reintroduce by
 * accident while improving an error message.
 */
@Requirement("FR-10")
class AuthenticateUserTest {

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-09-06T09:00:00Z"), ZoneOffset.UTC);
    private static final UUID INSTITUTION = UUID.randomUUID();
    private static final String PASSWORD = "correct horse battery staple";

    // ------------------------------------------------------------------ stubs

    /** Hashing that is deterministic and instant, so the tests are about the rules. */
    private static final class FakeHasher implements PasswordHasher {
        @Override
        public String hash(String rawPassword) {
            return "hashed:" + rawPassword;
        }

        @Override
        public boolean matches(String rawPassword, String storedHash) {
            return storedHash != null && storedHash.equals("hashed:" + rawPassword);
        }
    }

    private static final class Users implements UserRepository {
        private final Map<String, UserAccount> byEmail = new HashMap<>();

        @Override
        public Optional<UserAccount> findByEmail(String email) {
            return Optional.ofNullable(byEmail.get(email));
        }

        @Override
        public Optional<UserAccount> findById(UUID id) {
            return byEmail.values().stream().filter(u -> u.id().equals(id)).findFirst();
        }

        @Override
        public UserAccount save(UserAccount account) {
            byEmail.put(account.email(), account);
            return account;
        }
        // This class only authenticates, so the account-management half of the port is refused
        // rather than implemented. A stub that quietly answers a call nobody expects is a stub
        // that lets a test pass while the code under test does something surprising.
        @Override
        public java.util.List<UserAccount> findAll() {
            throw new UnsupportedOperationException("AuthenticateUser does not list accounts");
        }

        @Override
        public long count() {
            throw new UnsupportedOperationException("AuthenticateUser does not count accounts");
        }

    }

    /** Minimal issuer that records how many pairs it handed out. */
    private static final class RecordingIssuer implements TokenIssuer {
        private final List<UserAccount> issuedFor = new ArrayList<>();

        @Override
        public TokenIssuer.Tokens issue(UserAccount account) {
            issuedFor.add(account);
            return new TokenIssuer.Tokens("access", "refresh", Instant.now(NOW).plusSeconds(900));
        }

        @Override
        public TokenIssuer.Tokens refresh(String refreshToken) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int revokeSessionsFor(UUID userId) {
            // Signing in never revokes anything. A stub that quietly answers a call nobody
            // expects lets a test pass while the code under test does something surprising.
            throw new UnsupportedOperationException("signing in does not revoke sessions");
        }
    }

    private static final class InMemoryLedger implements LedgerRepository {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public AuditEntry append(Instant at, UUID actorId, String role, LedgerAction action,
                String subject, String payloadHash) {
            long seq = entries.size() + 1L;
            String prev = entries.isEmpty() ? Chain.GENESIS : entries.getLast().entryHash();
            String hash = Chain.entryHash(seq, at, actorId, role, action, subject, payloadHash, prev);
            AuditEntry entry =
                    new AuditEntry(seq, at, actorId, role, action, subject, payloadHash, prev, hash);
            entries.add(entry);
            return entry;
        }

        @Override
        public Optional<AuditEntry> head() {
            return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast());
        }

        @Override
        public List<AuditEntry> range(long from, long to) {
            return List.copyOf(entries);
        }

        @Override
        public List<AuditEntry> forSubject(String subjectRef) {
            return entries.stream().filter(e -> e.subjectRef().equals(subjectRef)).toList();
        }

        @Override
        public List<AuditEntry> between(Instant from, Instant to) {
            return List.copyOf(entries);
        }

        @Override
        public long headSequence() {
            return entries.size();
        }
    }

    // ---------------------------------------------------------------- fixture

    private Users users;
    private RecordingIssuer issuer;
    private InMemoryLedger ledger;
    private AuthenticateUser authenticate;

    private UserAccount save(Role role, UUID institutionId, String secret, boolean enrolled) {
        UUID id = UUID.randomUUID();
        return users.save(new UserAccount(id, "someone@example.ac.zw", "Someone", role,
                institutionId, "hashed:" + PASSWORD, secret, enrolled, false));
    }

    @BeforeEach
    void setUp() {
        users = new Users();
        issuer = new RecordingIssuer();
        ledger = new InMemoryLedger();
        authenticate = new AuthenticateUser(
                users, new FakeHasher(), issuer, new AppendEntry(ledger, NOW), NOW);
    }

    private AuthenticateUser.SignInResult signIn(String password, String code) {
        return authenticate.signIn(
                new AuthenticateUser.Command("someone@example.ac.zw", password, code));
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("a role with no second factor")
    class WithoutMfa {

        @BeforeEach
        void auditor() {
            save(Role.AUDITOR, null, null, false);
        }

        @Test
        @DisplayName("signs in with the right password")
        void signsIn() {
            var result = signIn(PASSWORD, null);

            assertThat(result).isInstanceOf(AuthenticateUser.SignInResult.Authenticated.class);
            assertThat(issuer.issuedFor).hasSize(1);
        }

        @Test
        @DisplayName("records the sign-in against the user")
        void recordsSuccess() {
            signIn(PASSWORD, null);

            assertThat(ledger.entries).hasSize(1);
            assertThat(ledger.entries.getFirst().action()).isEqualTo(LedgerAction.USER_LOGGED_IN);
            assertThat(ledger.entries.getFirst().actorRole()).isEqualTo("AUDITOR");
        }

        @Test
        @DisplayName("normalises the email, so case and spacing do not lock anyone out")
        void normalisesEmail() {
            var result = authenticate.signIn(new AuthenticateUser.Command(
                    "  SomeOne@Example.AC.ZW  ", PASSWORD, null));

            assertThat(result).isInstanceOf(AuthenticateUser.SignInResult.Authenticated.class);
        }
    }

    @Nested
    @DisplayName("failures are indistinguishable to the caller")
    class UniformFailures {

        @Test
        @DisplayName("a wrong password")
        void wrongPassword() {
            save(Role.AUDITOR, null, null, false);

            assertThatThrownBy(() -> signIn("wrong", null))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class)
                    .hasMessage("Those credentials were not accepted.");
        }

        @Test
        @DisplayName("an account that does not exist, with the same message")
        void unknownAccount() {
            assertThatThrownBy(() -> signIn(PASSWORD, null))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class)
                    .hasMessage("Those credentials were not accepted.");
        }

        @Test
        @DisplayName("a disabled account, with the same message again")
        void disabledAccount() {
            users.save(new UserAccount(UUID.randomUUID(), "someone@example.ac.zw", "S",
                    Role.AUDITOR, null, "hashed:" + PASSWORD, null, false, true));

            assertThatThrownBy(() -> signIn(PASSWORD, null))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class)
                    .hasMessage("Those credentials were not accepted.");
        }

        @Test
        @DisplayName("but the ledger keeps the detail the response withholds")
        void ledgerDistinguishesWhatTheResponseDoesNot() {
            // A run of BAD_PASSWORD entries against one account is a brute-force attempt; a run
            // of UNKNOWN_USER entries is somebody working through a list of addresses. The
            // login form must not tell them apart, and an operator must be able to.
            save(Role.AUDITOR, null, null, false);

            assertThatThrownBy(() -> signIn("wrong", null))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class);
            assertThatThrownBy(() -> authenticate.signIn(
                    new AuthenticateUser.Command("nobody@example.ac.zw", PASSWORD, null)))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class);

            assertThat(ledger.entries).hasSize(2);
            assertThat(ledger.entries).allMatch(
                    entry -> entry.action() == LedgerAction.USER_LOGIN_FAILED);
            assertThat(ledger.entries.getFirst().payloadHash())
                    .isNotEqualTo(ledger.entries.get(1).payloadHash());
        }

        @Test
        @DisplayName("no tokens are issued for any of them")
        void noTokensOnFailure() {
            assertThatThrownBy(() -> signIn(PASSWORD, null))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class);

            assertThat(issuer.issuedFor).isEmpty();
        }
    }

    @Nested
    @DisplayName("a role that requires a second factor")
    class WithMfa {

        @Test
        @DisplayName("is offered enrolment on first use rather than simply refused")
        void offersEnrolment() {
            save(Role.REGISTRAR, INSTITUTION, null, false);

            var result = signIn(PASSWORD, null);

            assertThat(result)
                    .isInstanceOf(AuthenticateUser.SignInResult.EnrolmentRequired.class);
            var enrolment = (AuthenticateUser.SignInResult.EnrolmentRequired) result;
            assertThat(enrolment.secret()).isNotBlank();
            assertThat(issuer.issuedFor).isEmpty();
        }

        @Test
        @DisplayName("persists the secret it hands out")
        void persistsTheSecret() {
            // The first implementation threw an exception here, which rolled back the
            // transaction and discarded the secret the user had just been shown. Every
            // enrolment then failed. This test is the regression guard.
            save(Role.REGISTRAR, INSTITUTION, null, false);

            var result = (AuthenticateUser.SignInResult.EnrolmentRequired) signIn(PASSWORD, null);

            assertThat(users.findByEmail("someone@example.ac.zw").orElseThrow().mfaSecret())
                    .isEqualTo(result.secret());
        }

        @Test
        @DisplayName("reuses a half-finished enrolment rather than issuing a new secret")
        void reusesPendingSecret() {
            // Generating a fresh secret on every attempt would invalidate the QR code the user
            // just scanned, so a second try could never succeed.
            save(Role.REGISTRAR, INSTITUTION, null, false);

            var first = (AuthenticateUser.SignInResult.EnrolmentRequired) signIn(PASSWORD, null);
            var second = (AuthenticateUser.SignInResult.EnrolmentRequired) signIn(PASSWORD, null);

            assertThat(second.secret()).isEqualTo(first.secret());
        }

        @Test
        @DisplayName("completes enrolment when the code proves the secret was received")
        void completesEnrolment() {
            save(Role.REGISTRAR, INSTITUTION, null, false);
            var offered = (AuthenticateUser.SignInResult.EnrolmentRequired) signIn(PASSWORD, null);
            String code = Totp.codeAt(offered.secret(), Instant.now(NOW));

            boolean enrolled =
                    authenticate.completeMfaEnrolment("someone@example.ac.zw", code);

            assertThat(enrolled).isTrue();
            assertThat(users.findByEmail("someone@example.ac.zw").orElseThrow().mfaEnrolled())
                    .isTrue();
        }

        @Test
        @DisplayName("refuses enrolment when the code does not match")
        void refusesBadEnrolmentCode() {
            save(Role.REGISTRAR, INSTITUTION, null, false);
            signIn(PASSWORD, null);

            assertThat(authenticate.completeMfaEnrolment("someone@example.ac.zw", "000000"))
                    .isFalse();
        }

        @Test
        @DisplayName("refuses enrolment for an unknown account")
        void refusesEnrolmentForUnknownAccount() {
            assertThatThrownBy(() ->
                    authenticate.completeMfaEnrolment("nobody@example.ac.zw", "123456"))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class);
        }

        @Test
        @DisplayName("signs in once enrolled and given a current code")
        void signsInWithSecondFactor() {
            String secret = Totp.generateSecret();
            save(Role.REGISTRAR, INSTITUTION, secret, true);

            var result = signIn(PASSWORD, Totp.codeAt(secret, Instant.now(NOW)));

            assertThat(result).isInstanceOf(AuthenticateUser.SignInResult.Authenticated.class);
        }

        @Test
        @DisplayName("refuses a wrong or missing code, with the same message as a wrong password")
        void refusesBadSecondFactor() {
            String secret = Totp.generateSecret();
            save(Role.REGISTRAR, INSTITUTION, secret, true);

            assertThatThrownBy(() -> signIn(PASSWORD, "000000"))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class)
                    .hasMessage("Those credentials were not accepted.");
            assertThatThrownBy(() -> signIn(PASSWORD, null))
                    .isInstanceOf(AuthenticateUser.AuthenticationFailed.class)
                    .hasMessage("Those credentials were not accepted.");
        }
    }
}
