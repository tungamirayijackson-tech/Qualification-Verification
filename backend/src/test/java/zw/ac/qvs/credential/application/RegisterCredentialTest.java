package zw.ac.qvs.credential.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.CredentialStatus;
import zw.ac.qvs.credential.domain.Holder;
import zw.ac.qvs.credential.domain.Institution;
import zw.ac.qvs.credential.domain.NqfLevel;
import zw.ac.qvs.credential.domain.Qualification;
import zw.ac.qvs.credential.domain.Serial;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.application.LedgerRepository;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.Chain;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.testsupport.Requirement;
import zw.ac.qvs.verification.application.CredentialSigner;
import zw.ac.qvs.verification.application.KeyVault;
import zw.ac.qvs.verification.domain.SigningKey;

/**
 * FR-01, with the acceptance criterion asserted directly: a credential is registered and
 * signed, and an institution whose accreditation has lapsed is refused.
 *
 * <p>No mocking framework. Every collaborator is a small in-memory stand-in, which makes the
 * test read as a description of the rule rather than a description of the interactions.
 */
@Requirement({"FR-01", "FR-05"})
class RegisterCredentialTest {

    private static final Clock TODAY =
            Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC);
    private static final UUID INSTITUTION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID QUALIFICATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID REGISTRAR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String SALT = "test-salt";
    private static final String KID = "inst-11111111-2026-01";

    // ------------------------------------------------------------------ stubs

    private static final class Institutions implements InstitutionRepository {
        private final Map<UUID, Institution> rows = new HashMap<>();

        @Override
        public List<Institution> findAll() {
            return List.copyOf(rows.values());
        }

        @Override
        public Optional<Institution> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public Optional<Institution> findByProviderNumber(String providerNumber) {
            return rows.values().stream()
                    .filter(row -> row.providerNumber().equals(providerNumber))
                    .findFirst();
        }

        @Override
        public Institution save(Institution institution) {
            rows.put(institution.id(), institution);
            return institution;
        }
    }

    private static final class Qualifications implements QualificationRepository {
        private final Map<UUID, Qualification> rows = new HashMap<>();

        @Override
        public Optional<Qualification> findById(UUID id) {
            return Optional.ofNullable(rows.get(id));
        }

        @Override
        public List<Qualification> findByInstitution(UUID institutionId) {
            return rows.values().stream()
                    .filter(q -> q.institutionId().equals(institutionId)).toList();
        }

        @Override
        public Qualification save(Qualification qualification) {
            rows.put(qualification.id(), qualification);
            return qualification;
        }
    }

    private static final class Holders implements HolderRepository {
        private final Map<String, Holder> byHash = new HashMap<>();

        @Override
        public Optional<Holder> findByNationalIdHash(String nationalIdHash) {
            return Optional.ofNullable(byHash.get(nationalIdHash));
        }

        @Override
        public Optional<Holder> findById(UUID id) {
            return byHash.values().stream().filter(h -> h.id().equals(id)).findFirst();
        }

        @Override
        public Holder save(Holder holder) {
            byHash.put(holder.nationalIdHash(), holder);
            return holder;
        }

        @Override
        public Holder insert(Holder holder) {
            return save(holder);
        }
    }

    private static final class Credentials implements CredentialRepository {
        private final Map<String, Credential> bySerial = new HashMap<>();
        private int sequence;

        @Override
        public Optional<Credential> findBySerial(Serial serial) {
            return Optional.ofNullable(bySerial.get(serial.value()));
        }

        @Override
        public Optional<Credential> findById(UUID id) {
            return bySerial.values().stream().filter(c -> c.id().equals(id)).findFirst();
        }

        @Override
        public List<Credential> findByHolder(UUID holderId) {
            return bySerial.values().stream().filter(c -> c.holderId().equals(holderId)).toList();
        }

        @Override
        public Credential save(Credential credential) {
            bySerial.put(credential.serial().value(), credential);
            return credential;
        }

        @Override
        public Credential insert(Credential credential) {
            return save(credential);
        }

        @Override
        public int reserveSerialSequence(String institutionCode, int year) {
            return ++sequence;
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

    /** Records what it was asked to sign, so the test can assert on the signed bytes. */
    private static final class RecordingSigner implements CredentialSigner {
        private final List<String> signed = new ArrayList<>();

        @Override
        public String signDetached(byte[] canonicalBytes, String kid) {
            signed.add(new String(canonicalBytes, java.nio.charset.StandardCharsets.UTF_8));
            return "eyJhbGciOiJFZERTQSJ9.." + Hashing.sha256Hex(new String(canonicalBytes,
                    java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public boolean verifyDetached(byte[] canonicalBytes, String detachedJws, SigningKey key) {
            return true;
        }
    }

    private static final class InMemoryLedger implements LedgerRepository {
        private final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public AuditEntry append(Instant occurredAt, UUID actorId, String actorRole,
                LedgerAction action, String subjectRef, String payloadHash) {
            long seq = entries.size() + 1L;
            String prev = entries.isEmpty() ? Chain.GENESIS : entries.getLast().entryHash();
            String hash = Chain.entryHash(seq, occurredAt, actorId, actorRole, action,
                    subjectRef, payloadHash, prev);
            AuditEntry entry = new AuditEntry(seq, occurredAt, actorId, actorRole, action,
                    subjectRef, payloadHash, prev, hash);
            entries.add(entry);
            return entry;
        }

        @Override
        public Optional<AuditEntry> head() {
            return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getLast());
        }

        @Override
        public List<AuditEntry> range(long fromSeq, long toSeq) {
            return entries.stream()
                    .filter(e -> e.seq() >= fromSeq && e.seq() <= toSeq).toList();
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

    // ------------------------------------------------------------------ fixture

    private Institutions institutions;
    private Qualifications qualifications;
    private Holders holders;
    private Credentials credentials;
    private Vault vault;
    private RecordingSigner signer;
    private InMemoryLedger ledger;
    private RegisterCredential registerCredential;

    private static Institution institution(LocalDate accreditedUntil) {
        return new Institution(INSTITUTION, "Example University", "ZW", "PR-0142",
                accreditedUntil, KID);
    }

    private static Qualification qualification(UUID institutionId, boolean phasedOut) {
        return new Qualification(QUALIFICATION, institutionId, "BSc Computer Science",
                new NqfLevel(7), 360, "SAQA-12345", phasedOut);
    }

    private static RegisterCredential.Command command(LocalDate awardedOn) {
        return new RegisterCredential.Command(INSTITUTION, QUALIFICATION, "63-1234567K42",
                "Thandeka N. Mahlangu", LocalDate.of(1998, 1, 1), awardedOn, REGISTRAR);
    }

    @BeforeEach
    void setUp() {
        institutions = new Institutions();
        qualifications = new Qualifications();
        holders = new Holders();
        credentials = new Credentials();
        vault = new Vault();
        signer = new RecordingSigner();
        ledger = new InMemoryLedger();

        institutions.rows.put(INSTITUTION, institution(LocalDate.of(2030, 12, 31)));
        qualifications.save(qualification(INSTITUTION, false));
        vault.key = new SigningKey(KID, INSTITUTION, "{\"kty\":\"OKP\"}",
                LocalDate.of(2020, 1, 1), null);

        registerCredential = new RegisterCredential(institutions, qualifications, holders,
                credentials, vault, signer, new AppendEntry(ledger, TODAY), TODAY, SALT);
    }

    // ------------------------------------------------------------------ tests

    @Nested
    @DisplayName("the happy path")
    class Registers {

        @Test
        @DisplayName("issues a signed credential with a well-formed serial")
        void registersAndSigns() {
            Credential result = registerCredential.register(command(LocalDate.of(2026, 4, 11)));

            assertThat(result.status()).isEqualTo(CredentialStatus.ISSUED);
            assertThat(result.serial().value()).isEqualTo("ZW-PR0142-2026-000001");
            assertThat(result.detachedJws()).isNotBlank();
            assertThat(result.keyId()).isEqualTo(KID);
            assertThat(result.isRevoked()).isFalse();
        }

        @Test
        @DisplayName("signs the canonical claims, including the fields a forger would target")
        void signsTheRightBytes() {
            registerCredential.register(command(LocalDate.of(2026, 4, 11)));

            assertThat(signer.signed).hasSize(1);
            assertThat(signer.signed.getFirst())
                    .contains("\"qua\":\"BSc Computer Science\"")
                    .contains("\"nqf\":7")
                    .contains("\"nam\":\"Thandeka N. Mahlangu\"")
                    .contains("\"awd\":\"2026-04-11\"")
                    .contains("\"kid\":\"" + KID + "\"");
        }

        @Test
        @DisplayName("stores the exact bytes that were signed, not a re-derivation")
        void storesSignedBytes() {
            Credential result = registerCredential.register(command(LocalDate.of(2026, 4, 11)));

            assertThat(result.payloadCanonical()).isEqualTo(signer.signed.getFirst());
        }

        @Test
        @DisplayName("never stores the national ID, only a salted hash of it")
        void hashesTheNationalId() {
            registerCredential.register(command(LocalDate.of(2026, 4, 11)));

            Holder stored = holders.byHash.values().iterator().next();
            // Hashed in canonical form, the same as search hashes it -- these two used to
            // differ by a trim, which would have become a real mismatch the moment the format
            // allowed a hyphen.
            assertThat(stored.nationalIdHash())
                    .isEqualTo(Hashing.sha256Hex(SALT + "631234567K42"))
                    .doesNotContain("63-1234567K42");
            assertThat(signer.signed.getFirst()).doesNotContain("63-1234567K42");
        }

        @Test
        @DisplayName("appends an issuance entry naming the registrar and the serial")
        void appendsToLedger() {
            Credential result = registerCredential.register(command(LocalDate.of(2026, 4, 11)));

            assertThat(ledger.entries).hasSize(1);
            AuditEntry entry = ledger.entries.getFirst();
            assertThat(entry.action()).isEqualTo(LedgerAction.CREDENTIAL_ISSUED);
            assertThat(entry.subjectRef()).isEqualTo(result.serial().value());
            assertThat(entry.actorId()).isEqualTo(REGISTRAR);
            assertThat(entry.prevHash()).isEqualTo(Chain.GENESIS);
            assertThat(entry.isSelfConsistent()).isTrue();
        }

        @Test
        @DisplayName("the ledger stores a hash of the detail, never the detail itself")
        void ledgerStoresOnlyHashes() {
            registerCredential.register(command(LocalDate.of(2026, 4, 11)));

            AuditEntry entry = ledger.entries.getFirst();
            assertThat(entry.payloadHash()).matches("[0-9a-f]{64}");
            assertThat(entry.subjectRef()).doesNotContain("Thandeka");
        }

        @Test
        @DisplayName("reuses an existing holder rather than creating a duplicate")
        void reusesHolder() {
            registerCredential.register(command(LocalDate.of(2026, 4, 11)));
            registerCredential.register(command(LocalDate.of(2026, 4, 12)));

            assertThat(holders.byHash).hasSize(1);
        }

        @Test
        @DisplayName("serials increment within an institution and year")
        void serialsIncrement() {
            Credential first = registerCredential.register(command(LocalDate.of(2026, 4, 11)));
            Credential second = registerCredential.register(command(LocalDate.of(2026, 4, 12)));

            assertThat(first.serial().value()).endsWith("000001");
            assertThat(second.serial().value()).endsWith("000002");
        }

        @Test
        @DisplayName("an award backdated into the accredited period is accepted")
        void backdatedAwardWithinAccreditation() {
            Credential result = registerCredential.register(command(LocalDate.of(2021, 6, 30)));

            assertThat(result.awardedOn()).isEqualTo(LocalDate.of(2021, 6, 30));
        }
    }

    @Nested
    @DisplayName("refusals leave no trace")
    class Refuses {

        @Test
        @DisplayName("an institution whose accreditation lapsed before the award date")
        void lapsedAccreditation() {
            institutions.rows.put(INSTITUTION, institution(LocalDate.of(2024, 1, 31)));

            assertThatThrownBy(() -> registerCredential.register(command(LocalDate.of(2026, 4, 11))))
                    .isInstanceOf(RegistrationRejected.class)
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.INSTITUTION_NOT_ACCREDITED);

            // The point of asserting this: a refused registration must not appear in the
            // register, and must not appear in the audit trail as though it had.
            assertThat(credentials.bySerial).isEmpty();
            assertThat(ledger.entries).isEmpty();
            assertThat(signer.signed).isEmpty();
        }

        @Test
        @DisplayName("but the same institution may still record an award made while accredited")
        void lapsedInstitutionCanStillBackdate() {
            institutions.rows.put(INSTITUTION, institution(LocalDate.of(2024, 1, 31)));

            Credential result = registerCredential.register(command(LocalDate.of(2023, 6, 30)));

            assertThat(result.awardedOn()).isEqualTo(LocalDate.of(2023, 6, 30));
        }

        @Test
        @DisplayName("an award dated in the future")
        void futureAward() {
            assertThatThrownBy(() -> registerCredential.register(command(LocalDate.of(2027, 1, 1))))
                    .isInstanceOf(RegistrationRejected.class)
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.IMPOSSIBLE_AWARD_DATE);
        }

        @Test
        @DisplayName("a qualification that has been phased out")
        void phasedOutQualification() {
            qualifications.save(qualification(INSTITUTION, true));

            assertThatThrownBy(() -> registerCredential.register(command(LocalDate.of(2026, 4, 11))))
                    .isInstanceOf(RegistrationRejected.class)
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.QUALIFICATION_PHASED_OUT);
        }

        @Test
        @DisplayName("a qualification belonging to a different institution")
        void qualificationFromElsewhere() {
            qualifications.save(qualification(UUID.randomUUID(), false));

            assertThatThrownBy(() -> registerCredential.register(command(LocalDate.of(2026, 4, 11))))
                    .isInstanceOf(RegistrationRejected.class)
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.QUALIFICATION_NOT_OFFERED_HERE);
        }

        @Test
        @DisplayName("an institution with no signing key, because nothing could vouch for it")
        void noSigningKey() {
            vault.key = null;

            assertThatThrownBy(() -> registerCredential.register(command(LocalDate.of(2026, 4, 11))))
                    .isInstanceOf(RegistrationRejected.class)
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.INSTITUTION_HAS_NO_KEY);
        }

        @Test
        @DisplayName("an unknown institution or qualification")
        void unknownReferences() {
            var unknownInstitution = new RegisterCredential.Command(UUID.randomUUID(),
                    QUALIFICATION, "63-1234567K42", "Name", null,
                    LocalDate.of(2026, 4, 11), REGISTRAR);
            assertThatThrownBy(() -> registerCredential.register(unknownInstitution))
                    .isInstanceOf(RegistrationRejected.class)
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.UNKNOWN_REFERENCE);

            var unknownQualification = new RegisterCredential.Command(INSTITUTION,
                    UUID.randomUUID(), "63-1234567K42", "Name", null,
                    LocalDate.of(2026, 4, 11), REGISTRAR);
            assertThatThrownBy(() -> registerCredential.register(unknownQualification))
                    .isInstanceOf(RegistrationRejected.class)
                    .extracting(e -> ((RegistrationRejected) e).reason())
                    .isEqualTo(RegistrationRejected.Reason.UNKNOWN_REFERENCE);
        }
    }

    @Nested
    @DisplayName("key rotation")
    class Rotation {

        @Test
        @DisplayName("an award signs with the key that was valid on its award date")
        void resolvesKeyByAwardDate() {
            // Decision 05-A. The institution has rotated: the 2020 key is closed, a 2026 key
            // is current. An award conferred in 2021 must be signed by the key of the day.
            vault.key = new SigningKey("inst-11111111-2020-01", INSTITUTION, "{\"kty\":\"OKP\"}",
                    LocalDate.of(2020, 1, 1), LocalDate.of(2025, 12, 31));

            Credential result = registerCredential.register(command(LocalDate.of(2021, 6, 30)));

            assertThat(result.keyId()).isEqualTo("inst-11111111-2020-01");
            assertThat(signer.signed.getFirst()).contains("\"kid\":\"inst-11111111-2020-01\"");
        }
    }
}
