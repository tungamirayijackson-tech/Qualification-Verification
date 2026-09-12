package zw.ac.qvs.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.Chain;
import zw.ac.qvs.ledger.domain.ChainBreak;
import zw.ac.qvs.ledger.domain.ChainVerification;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.shared.domain.CurrentActor;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.testsupport.Requirement;

/**
 * Appending to the ledger, verifying it, and exporting it (FR-08, FR-09).
 */
@Requirement({"FR-08", "FR-09"})
class LedgerApplicationTest {

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-09-06T09:00:00Z"), ZoneOffset.UTC);
    private static final UUID AUDITOR = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final CurrentActor ACTOR = new CurrentActor(AUDITOR, "AUDITOR", null);

    /** A ledger that chains correctly, so the use cases under test are the only variable. */
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
        public List<AuditEntry> range(long fromSeq, long toSeq) {
            return entries.stream()
                    .filter(e -> e.seq() >= fromSeq && e.seq() <= toSeq)
                    .toList();
        }

        @Override
        public List<AuditEntry> forSubject(String subjectRef) {
            return entries.stream().filter(e -> e.subjectRef().equals(subjectRef)).toList();
        }

        @Override
        public List<AuditEntry> between(Instant from, Instant to) {
            return entries.stream()
                    .filter(e -> !e.occurredAt().isBefore(from) && e.occurredAt().isBefore(to))
                    .toList();
        }

        @Override
        public long headSequence() {
            return entries.size();
        }

        /** Replaces an entry's contents without recomputing its hash, as a tamperer would. */
        void tamperWith(int index, String newSubject) {
            AuditEntry original = entries.get(index);
            entries.set(index, new AuditEntry(original.seq(), original.occurredAt(),
                    original.actorId(), original.actorRole(), original.action(), newSubject,
                    original.payloadHash(), original.prevHash(), original.entryHash()));
        }
    }

    private InMemoryLedger ledger;
    private AppendEntry append;
    private VerifyChain verifyChain;
    private ExportTrail exportTrail;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        append = new AppendEntry(ledger, NOW);
        verifyChain = new VerifyChain(ledger);
        exportTrail = new ExportTrail(ledger, append, NOW);
    }

    private void seedThree() {
        append.record(AUDITOR, "REGISTRAR", LedgerAction.CREDENTIAL_ISSUED,
                "ZW-PR0142-2026-000001", "issued");
        append.recordAnonymous(LedgerAction.VERIFICATION_RUN, "ZW-PR0142-2026-000001", "VALID|WEB");
        append.record(AUDITOR, "REGISTRAR", LedgerAction.CREDENTIAL_REVOKED,
                "ZW-PR0142-2026-000001", "ACADEMIC_MISCONDUCT|");
    }

    @Nested
    @DisplayName("appending")
    class Appending {

        @Test
        @DisplayName("chains each entry to the one before it")
        void chains() {
            seedThree();

            assertThat(ledger.entries).hasSize(3);
            assertThat(ledger.entries.get(0).prevHash()).isEqualTo(Chain.GENESIS);
            assertThat(ledger.entries.get(1).prevHash())
                    .isEqualTo(ledger.entries.get(0).entryHash());
            assertThat(ledger.entries.get(2).prevHash())
                    .isEqualTo(ledger.entries.get(1).entryHash());
        }

        @Test
        @DisplayName("stores a hash of the detail, never the detail")
        void hashesTheDetail() {
            // A ledger that quoted holder names in its detail column would defeat the
            // minimisation the rest of the design works for.
            append.record(AUDITOR, "REGISTRAR", LedgerAction.CREDENTIAL_ISSUED,
                    "ZW-PR0142-2026-000001", "Thandeka N. Mahlangu");

            AuditEntry entry = ledger.entries.getFirst();
            assertThat(entry.payloadHash())
                    .isEqualTo(Hashing.sha256Hex("Thandeka N. Mahlangu"))
                    .doesNotContain("Thandeka");
        }

        @Test
        @DisplayName("records an anonymous public check as ANONYMOUS with no actor")
        void anonymousActor() {
            append.recordAnonymous(LedgerAction.VERIFICATION_RUN, "serial", "VALID|WEB");

            AuditEntry entry = ledger.entries.getFirst();
            assertThat(entry.actorId()).isNull();
            assertThat(entry.actorRole()).isEqualTo(AppendEntry.ANONYMOUS);
        }

        @Test
        @DisplayName("treats a null detail as an empty one rather than failing")
        void nullDetail() {
            append.record(AUDITOR, "REGISTRAR", LedgerAction.KEY_ROTATED, "inst", null);

            assertThat(ledger.entries.getFirst().payloadHash())
                    .isEqualTo(Hashing.sha256Hex(""));
        }
    }

    @Nested
    @DisplayName("verifying")
    class Verifying {

        @Test
        @DisplayName("an empty ledger is intact")
        void emptyLedger() {
            assertThat(verifyChain.verifyAll()).isEmpty();
        }

        @Test
        @DisplayName("an untouched chain recomputes end to end")
        void intactChain() {
            seedThree();

            assertThat(verifyChain.verifyAll()).isEmpty();
        }

        @Test
        @DisplayName("an edited entry is located by sequence number")
        void locatesTheEdit() {
            seedThree();
            ledger.tamperWith(1, "TAMPERED");

            var broken = verifyChain.verifyAll();

            assertThat(broken).isPresent();
            assertThat(broken.get().atSeq()).isEqualTo(2);
            assertThat(broken.get().kind()).isEqualTo(ChainBreak.Kind.ENTRY_ALTERED);
        }

        @Test
        @DisplayName("a subject's own entries can be checked for internal consistency")
        void verifiesOneSubject() {
            seedThree();

            assertThat(verifyChain.verifySubjectEntries("ZW-PR0142-2026-000001")).isEmpty();

            ledger.tamperWith(0, "ZW-PR0142-2026-000001");
            // The subject reference is unchanged, so the entry still hashes correctly.
            assertThat(verifyChain.verifySubjectEntries("ZW-PR0142-2026-000001")).isEmpty();
        }

        @Test
        @DisplayName("a subject scan reports an entry whose own hash no longer matches")
        void subjectScanFindsAnEdit() {
            seedThree();
            ledger.tamperWith(0, "ZW-PR0142-2026-999999");

            var broken = verifyChain.verifySubjectEntries("ZW-PR0142-2026-999999");

            assertThat(broken).isPresent();
            assertThat(broken.get().kind()).isEqualTo(ChainBreak.Kind.ENTRY_ALTERED);
        }
    }

    @Nested
    @DisplayName("exporting")
    class Exporting {

        @Test
        @DisplayName("produces a header and one row per entry")
        void csvShape() {
            seedThree();

            var export = exportTrail.asCsv(ACTOR, "ZW-PR0142-2026-000001", null, null);

            List<String> lines = export.content().lines().toList();
            assertThat(lines.getFirst()).startsWith("seq,occurred_at,actor_role,action");
            assertThat(lines).hasSize(4);
            assertThat(export.rowCount()).isEqualTo(3);
            assertThat(export.filename()).startsWith("qvs-audit-").endsWith(".csv");
        }

        @Test
        @DisplayName("records the export itself, with the row count")
        void exportIsLogged() {
            // An audit system whose own use leaves no trace is one where a quiet export of the
            // entire history looks exactly like nothing happening at all.
            seedThree();

            exportTrail.asCsv(ACTOR, "ZW-PR0142-2026-000001", null, null);

            AuditEntry recorded = ledger.entries.getLast();
            assertThat(recorded.action()).isEqualTo(LedgerAction.TRAIL_EXPORTED);
            assertThat(recorded.actorId()).isEqualTo(AUDITOR);
        }

        @Test
        @DisplayName("quotes fields, so a spreadsheet cannot silently misparse the trail")
        void quotesFields() {
            append.record(AUDITOR, "REGISTRAR", LedgerAction.CREDENTIAL_ISSUED,
                    "has,comma and \"quotes\"", "detail");

            var export = exportTrail.asCsv(ACTOR, "has,comma and \"quotes\"", null, null);

            assertThat(export.content()).contains("\"has,comma and \"\"quotes\"\"\"");
        }

        @Test
        @DisplayName("falls back to a date range when no subject is given")
        void rangeExport() {
            seedThree();

            var export = exportTrail.asCsv(ACTOR, null, null, null);

            assertThat(export.rowCount()).isEqualTo(3);
            assertThat(ledger.entries.getLast().subjectRef()).isEqualTo("range-export");
        }

        @Test
        @DisplayName("records a chain verification and what it found")
        void recordsChainVerification() {
            var result = new ChainVerification(Instant.now(NOW), 3, 3, null);

            exportTrail.recordChainVerification(ACTOR, result);

            AuditEntry recorded = ledger.entries.getLast();
            assertThat(recorded.action()).isEqualTo(LedgerAction.CHAIN_VERIFIED);
            assertThat(recorded.subjectRef()).isEqualTo("chain");
            assertThat(result.isIntact()).isTrue();
            assertThat(result.summary()).contains("chain intact");
        }

        @Test
        @DisplayName("a broken chain summarises how far the history can be trusted")
        void brokenSummary() {
            var broken = new ChainVerification(Instant.now(NOW), 10, 10,
                    new ChainBreak(4183, ChainBreak.Kind.ENTRY_ALTERED, "hash mismatch"));

            assertThat(broken.isIntact()).isFalse();
            assertThat(broken.brokenAt()).isPresent();
            assertThat(broken.summary())
                    .contains("trustworthy up to seq 4182")
                    .contains("4183");
        }
    }
}
