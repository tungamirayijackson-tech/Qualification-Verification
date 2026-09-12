package zw.ac.qvs.verification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.application.LedgerRepository;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.Chain;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.testsupport.Requirement;
import zw.ac.qvs.verification.domain.CheckOutcome;
import zw.ac.qvs.verification.domain.CredentialUnderVerification;
import zw.ac.qvs.verification.domain.SigningKey;
import zw.ac.qvs.verification.domain.VerificationCheck;
import zw.ac.qvs.verification.domain.Verdict;

/**
 * FR-06 and FR-07: what a verdict is made of, and what a stranger is allowed to learn.
 *
 * <p>The revoked case carries the most weight. FR-07 requires a withdrawn credential to keep a
 * valid signature, because the institution really did confer the award — so the assertions here
 * check that the verdict is REVOKED <em>and</em> that the signature check reads PASS. A test
 * that only asserted the verdict would pass against an implementation that destroyed the
 * signature on revocation, which is the mistake this design exists to avoid.
 */
@Requirement({"FR-06", "FR-07"})
class VerifyCredentialTest {

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-09-06T09:00:00Z"), ZoneOffset.UTC);
    private static final UUID INSTITUTION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CREDENTIAL = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String KID = "inst-11111111-2020-01";
    private static final String RAW_TOKEN = "a-share-token";

    // ------------------------------------------------------------------ stubs

    private static final class ReadModel implements VerificationReadModel {
        private CredentialUnderVerification row;

        @Override
        public Optional<CredentialUnderVerification> findByShareTokenHash(String hash) {
            return Optional.ofNullable(hash.equals(Hashing.sha256Hex(RAW_TOKEN)) ? row : null);
        }

        @Override
        public Optional<CredentialUnderVerification> findBySerialForConsole(String serial) {
            return Optional.ofNullable(row);
        }
    }

    private static final class Vault implements KeyVault {
        private SigningKey key;

        @Override
        public Optional<SigningKey> findByKid(String kid) {
            return Optional.ofNullable(key).filter(k -> k.kid().equals(kid));
        }

        @Override
        public Optional<SigningKey> currentKeyFor(UUID institutionId) {
            return Optional.ofNullable(key);
        }

        @Override
        public Optional<SigningKey> keyValidOn(UUID institutionId, LocalDate on) {
            return Optional.ofNullable(key).filter(k -> k.wasValidOn(on));
        }

        @Override
        public List<SigningKey> allKeysFor(UUID institutionId) {
            return key == null ? List.of() : List.of(key);
        }

        @Override
        public SigningKey rotate(UUID institutionId, LocalDate from) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class Signer implements CredentialSigner {
        private boolean verifies = true;

        @Override
        public String signDetached(byte[] canonicalBytes, String kid) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean verifyDetached(byte[] bytes, String jws, SigningKey key) {
            return verifies;
        }
    }

    private static final class Presence implements LedgerPresence {
        private boolean present = true;

        @Override
        public boolean wasIssuanceRecorded(String serial) {
            return present;
        }
    }

    private static final class Log implements VerificationLog {
        private final List<Record> records = new ArrayList<>();

        @Override
        public void record(Record record) {
            records.add(record);
        }

        @Override
        public long countByClientSince(String clientIpHash, Instant since) {
            return records.size();
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

    private ReadModel readModel;
    private Vault vault;
    private Signer signer;
    private Presence presence;
    private Log log;
    private InMemoryLedger ledger;
    private VerifyCredential verify;

    private static CredentialUnderVerification credential(
            boolean revoked, LocalDate accreditedUntil) {
        return new CredentialUnderVerification(
                CREDENTIAL, "ZW-PR0142-2026-000001", INSTITUTION, "Example University",
                accreditedUntil, "BSc Computer Science", 7, LocalDate.of(2026, 4, 11),
                KID, "eyJhbGciOiJFZERTQSJ9..sig", "{\"ser\":\"ZW-PR0142-2026-000001\"}",
                revoked, revoked ? Instant.parse("2026-08-01T00:00:00Z") : null,
                revoked ? "ACADEMIC_MISCONDUCT" : null,
                "T.N.M.", false, false);
    }

    @BeforeEach
    void setUp() {
        readModel = new ReadModel();
        vault = new Vault();
        signer = new Signer();
        presence = new Presence();
        log = new Log();
        ledger = new InMemoryLedger();

        readModel.row = credential(false, LocalDate.of(2030, 12, 31));
        vault.key = new SigningKey(KID, INSTITUTION, "{\"kty\":\"OKP\"}",
                LocalDate.of(2020, 1, 1), null);

        verify = new VerifyCredential(readModel, vault, signer, presence,
                new AppendEntry(ledger, NOW), log, NOW);
    }

    private VerifyCredential.Outcome check() {
        return verify.verify(RAW_TOKEN, VerifyCredential.Context.web("ip-hash"));
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("a genuine, current credential")
    class Valid {

        @Test
        @DisplayName("is VALID with all four checks passing")
        void allChecksPass() {
            var outcome = check();

            assertThat(outcome.verdict()).isInstanceOf(Verdict.Valid.class);
            assertThat(outcome.verdict().name()).isEqualTo("VALID");
            assertThat(outcome.verdict().checks())
                    .containsEntry(VerificationCheck.SIGNATURE, CheckOutcome.PASS)
                    .containsEntry(VerificationCheck.ISSUER_STANDING, CheckOutcome.PASS)
                    .containsEntry(VerificationCheck.REVOCATION, CheckOutcome.PASS)
                    .containsEntry(VerificationCheck.LEDGER_PRESENCE, CheckOutcome.PASS);
        }

        @Test
        @DisplayName("is recorded in the ledger and cites the entry back to the verifier")
        void recordsTheCheck() {
            var outcome = check();

            assertThat(ledger.entries).hasSize(1);
            assertThat(ledger.entries.getFirst().action()).isEqualTo(LedgerAction.VERIFICATION_RUN);
            assertThat(outcome.ledgerSeq()).isEqualTo(ledger.entries.getFirst().seq());
            assertThat(log.records).hasSize(1);
            assertThat(log.records.getFirst().verdict()).isEqualTo("VALID");
        }
    }

    @Nested
    @DisplayName("a withdrawn credential")
    class Revoked {

        @BeforeEach
        void withdraw() {
            readModel.row = credential(true, LocalDate.of(2030, 12, 31));
        }

        @Test
        @DisplayName("is REVOKED while its signature still verifies")
        void signatureSurvivesRevocation() {
            // The FR-07 case in one assertion pair. The award was conferred and later
            // withdrawn; both are true, and a verifier is shown both.
            var outcome = check();

            assertThat(outcome.verdict()).isInstanceOf(Verdict.Revoked.class);
            assertThat(outcome.verdict().checks())
                    .containsEntry(VerificationCheck.SIGNATURE, CheckOutcome.PASS)
                    .containsEntry(VerificationCheck.REVOCATION, CheckOutcome.FAIL);
        }

        @Test
        @DisplayName("carries the reason and the date it was withdrawn")
        void carriesReason() {
            var revoked = (Verdict.Revoked) check().verdict();

            assertThat(revoked.reason()).isEqualTo("ACADEMIC_MISCONDUCT");
            assertThat(revoked.revokedAt()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
        }
    }

    @Nested
    @DisplayName("everything a stranger must not be able to distinguish")
    class NotFound {

        @Test
        @DisplayName("an unknown token")
        void unknownToken() {
            var outcome = verify.verify("no-such-token", VerifyCredential.Context.web(null));

            assertThat(outcome.verdict().name()).isEqualTo("NOT_FOUND");
            assertThat(outcome.hasCredential()).isFalse();
            assertThat(outcome.verdict().checks()).isEmpty();
        }

        @Test
        @DisplayName("a null or blank token, without reaching the database")
        void blankToken() {
            assertThat(verify.verify(null, VerifyCredential.Context.web(null)).verdict().name())
                    .isEqualTo("NOT_FOUND");
            assertThat(verify.verify("  ", VerifyCredential.Context.web(null)).verdict().name())
                    .isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("an expired share token")
        void expiredToken() {
            readModel.row = new CredentialUnderVerification(
                    CREDENTIAL, "ZW-PR0142-2026-000001", INSTITUTION, "Example University",
                    LocalDate.of(2030, 12, 31), "BSc Computer Science", 7,
                    LocalDate.of(2026, 4, 11), KID, "jws", "{}", false, null, null,
                    "T.N.M.", true, false);

            assertThat(check().verdict().name()).isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("a withdrawn share token")
        void withdrawnToken() {
            readModel.row = new CredentialUnderVerification(
                    CREDENTIAL, "ZW-PR0142-2026-000001", INSTITUTION, "Example University",
                    LocalDate.of(2030, 12, 31), "BSc Computer Science", 7,
                    LocalDate.of(2026, 4, 11), KID, "jws", "{}", false, null, null,
                    "T.N.M.", false, true);

            assertThat(check().verdict().name()).isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("a credential whose signature no longer verifies")
        void tamperedRecord() {
            // Somebody edited the register directly. The stranger learns nothing beyond
            // not-found; the auditor learns exactly which check failed.
            signer.verifies = false;

            var outcome = check();

            assertThat(outcome.verdict().name()).isEqualTo("NOT_FOUND");
            assertThat(outcome.verdict().checks()).isEmpty();
            assertThat(((Verdict.NotFound) outcome.verdict()).failedCheck())
                    .isEqualTo(VerificationCheck.SIGNATURE);
            assertThat(log.records.getFirst().failedCheck()).isEqualTo("SIGNATURE");
        }

        @Test
        @DisplayName("a credential whose issuance is missing from the ledger")
        void missingFromLedger() {
            presence.present = false;

            var outcome = check();

            assertThat(outcome.verdict().name()).isEqualTo("NOT_FOUND");
            assertThat(((Verdict.NotFound) outcome.verdict()).failedCheck())
                    .isEqualTo(VerificationCheck.LEDGER_PRESENCE);
        }

        @Test
        @DisplayName("an award made after the institution's accreditation had lapsed")
        void issuerLackedStanding() {
            readModel.row = credential(false, LocalDate.of(2024, 1, 31));

            var outcome = check();

            assertThat(outcome.verdict().name()).isEqualTo("NOT_FOUND");
            assertThat(((Verdict.NotFound) outcome.verdict()).failedCheck())
                    .isEqualTo(VerificationCheck.ISSUER_STANDING);
        }

        @Test
        @DisplayName("a credential signed with a key that was not valid on the award date")
        void keyNotValidThen() {
            // Decision 05-A from the other direction: a key whose window opened after the
            // award cannot have signed it, whatever the row claims.
            vault.key = new SigningKey(KID, INSTITUTION, "{\"kty\":\"OKP\"}",
                    LocalDate.of(2026, 6, 1), null);

            assertThat(check().verdict().name()).isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("a credential whose signing key belongs to another institution")
        void keyFromAnotherInstitution() {
            vault.key = new SigningKey(KID, UUID.randomUUID(), "{\"kty\":\"OKP\"}",
                    LocalDate.of(2020, 1, 1), null);

            assertThat(check().verdict().name()).isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("a credential whose key is unknown to the vault")
        void unknownKey() {
            vault.key = null;

            assertThat(check().verdict().name()).isEqualTo("NOT_FOUND");
        }

        @Test
        @DisplayName("every failure is still recorded, because failures are the interesting ones")
        void failuresAreRecorded() {
            signer.verifies = false;

            check();

            assertThat(ledger.entries).hasSize(1);
            assertThat(log.records).hasSize(1);
            assertThat(log.records.getFirst().verdict()).isEqualTo("NOT_FOUND");
        }
    }
}
