package zw.ac.qvs.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.testsupport.Requirement;

/**
 * FR-08's evidence: the chain recomputes end-to-end, and a break is located exactly.
 */
@Requirement("FR-08")
class ChainTest {

    private static final Instant T0 = Instant.parse("2026-09-05T09:00:00Z");
    private static final UUID REGISTRAR = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");

    /** Builds a well-formed chain of n entries, each linked to its predecessor. */
    private static List<AuditEntry> chainOf(int n) {
        List<AuditEntry> entries = new ArrayList<>();
        String prev = Chain.GENESIS;
        for (int i = 0; i < n; i++) {
            long seq = 4181 + i;
            Instant at = T0.plusSeconds(i * 60L);
            String payload = Hashing.sha256Hex("payload-" + i);
            String hash = Chain.entryHash(seq, at, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "ZW-UNI-2026-00048" + i, payload, prev);
            entries.add(new AuditEntry(seq, at, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "ZW-UNI-2026-00048" + i, payload, prev, hash));
            prev = hash;
        }
        return entries;
    }

    @Nested
    @DisplayName("hashing")
    class Hashes {

        @Test
        @DisplayName("is deterministic for identical input")
        void deterministic() {
            String first = Chain.entryHash(1, T0, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Chain.GENESIS, Chain.GENESIS);
            String second = Chain.entryHash(1, T0, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Chain.GENESIS, Chain.GENESIS);

            assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("every field changes the hash")
        void everyFieldParticipates() {
            String baseline = Chain.entryHash(1, T0, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Chain.GENESIS, Chain.GENESIS);

            assertThat(Chain.entryHash(2, T0, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Chain.GENESIS, Chain.GENESIS))
                    .isNotEqualTo(baseline);
            assertThat(Chain.entryHash(1, T0.plusSeconds(1), REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Chain.GENESIS, Chain.GENESIS))
                    .isNotEqualTo(baseline);
            assertThat(Chain.entryHash(1, T0, UUID.randomUUID(), "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Chain.GENESIS, Chain.GENESIS))
                    .isNotEqualTo(baseline);
            assertThat(Chain.entryHash(1, T0, REGISTRAR, "AUDITOR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Chain.GENESIS, Chain.GENESIS))
                    .isNotEqualTo(baseline);
            assertThat(Chain.entryHash(1, T0, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_REVOKED, "s", Chain.GENESIS, Chain.GENESIS))
                    .isNotEqualTo(baseline);
            assertThat(Chain.entryHash(1, T0, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "t", Chain.GENESIS, Chain.GENESIS))
                    .isNotEqualTo(baseline);
            assertThat(Chain.entryHash(1, T0, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Hashing.sha256Hex("x"), Chain.GENESIS))
                    .isNotEqualTo(baseline);
            assertThat(Chain.entryHash(1, T0, REGISTRAR, "REGISTRAR",
                    LedgerAction.CREDENTIAL_ISSUED, "s", Chain.GENESIS, Hashing.sha256Hex("y")))
                    .isNotEqualTo(baseline);
        }

        @Test
        @DisplayName("field boundaries cannot be shifted to forge a matching hash")
        void encodingIsInjective() {
            // Without length prefixing, ("ab", "c") and ("a", "bc") would hash identically,
            // which would let a tamperer move characters between fields and keep the hash.
            String left = Chain.entryHash(1, T0, null, "AB",
                    LedgerAction.VERIFICATION_RUN, "C", Chain.GENESIS, Chain.GENESIS);
            String right = Chain.entryHash(1, T0, null, "A",
                    LedgerAction.VERIFICATION_RUN, "BC", Chain.GENESIS, Chain.GENESIS);

            assertThat(left).isNotEqualTo(right);
        }

        @Test
        @DisplayName("an anonymous actor hashes differently from a named one")
        void anonymousActor() {
            String anonymous = Chain.entryHash(1, T0, null, "ANONYMOUS",
                    LedgerAction.VERIFICATION_RUN, "s", Chain.GENESIS, Chain.GENESIS);
            String named = Chain.entryHash(1, T0, REGISTRAR, "ANONYMOUS",
                    LedgerAction.VERIFICATION_RUN, "s", Chain.GENESIS, Chain.GENESIS);

            assertThat(anonymous).isNotEqualTo(named);
        }
    }

    @Nested
    @DisplayName("verification")
    class Verification {

        @Test
        @DisplayName("an intact chain reports no break")
        void intact() {
            assertThat(Chain.findFirstBreak(chainOf(10))).isEmpty();
            assertThat(Chain.findFirstBreakFromGenesis(chainOf(10))).isEmpty();
        }

        @Test
        @DisplayName("an empty or single-entry chain is trivially intact")
        void degenerate() {
            assertThat(Chain.findFirstBreak(List.of())).isEmpty();
            assertThat(Chain.findFirstBreak(chainOf(1))).isEmpty();
        }

        @Test
        @DisplayName("an edited entry is reported at its own sequence number")
        void editedEntryIsNamed() {
            // The money shot from the demo script: edit one field, run verification, watch it
            // name the exact row.
            List<AuditEntry> entries = new ArrayList<>(chainOf(5));
            AuditEntry original = entries.get(2);
            entries.set(2, new AuditEntry(original.seq(), original.occurredAt(),
                    original.actorId(), original.actorRole(), original.action(),
                    "TAMPERED-REF", original.payloadHash(), original.prevHash(),
                    original.entryHash()));

            var found = Chain.findFirstBreak(entries);

            assertThat(found).isPresent();
            assertThat(found.get().atSeq()).isEqualTo(4183);
            assertThat(found.get().kind()).isEqualTo(ChainBreak.Kind.ENTRY_ALTERED);
            assertThat(found.get().summary()).contains("4183");
        }

        @Test
        @DisplayName("a removed entry breaks the link at the following sequence number")
        void removedEntryBreaksLink() {
            List<AuditEntry> entries = new ArrayList<>(chainOf(5));
            entries.remove(2);

            var found = Chain.findFirstBreak(entries);

            assertThat(found).isPresent();
            assertThat(found.get().kind()).isEqualTo(ChainBreak.Kind.LINK_BROKEN);
            assertThat(found.get().atSeq()).isEqualTo(4184);
        }

        @Test
        @DisplayName("reordered entries are detected")
        void reordered() {
            List<AuditEntry> entries = new ArrayList<>(chainOf(5));
            java.util.Collections.swap(entries, 1, 3);

            assertThat(Chain.findFirstBreak(entries))
                    .get()
                    .extracting(ChainBreak::kind)
                    .isIn(ChainBreak.Kind.LINK_BROKEN, ChainBreak.Kind.OUT_OF_ORDER);
        }

        @Test
        @DisplayName("truncating the head of the history is detected")
        void headTruncated() {
            // A self-consistent chain that simply starts later. Only the genesis anchor
            // catches this, which is why chain verification checks it separately.
            List<AuditEntry> entries = new ArrayList<>(chainOf(5)).subList(2, 5);

            assertThat(Chain.findFirstBreak(entries)).isEmpty();
            assertThat(Chain.findFirstBreakFromGenesis(entries))
                    .get()
                    .extracting(ChainBreak::kind)
                    .isEqualTo(ChainBreak.Kind.NOT_ANCHORED);
        }

        @Test
        @DisplayName("a consistently re-chained tail is still caught by the genesis anchor")
        void reChainedTail() {
            // The strongest attack a single writer can mount: rewrite everything after the
            // edit so each hash links correctly. The chain then verifies internally -- and the
            // report should say so plainly. What still fails is the anchor, unless the
            // attacker also rewrites from entry one.
            List<AuditEntry> tail = chainOf(3).subList(1, 3);

            assertThat(Chain.findFirstBreakFromGenesis(tail)).isPresent();
        }
    }

    @Nested
    @DisplayName("entry invariants")
    class Invariants {

        @Test
        @DisplayName("a malformed hash cannot be stored")
        void rejectsBadHashes() {
            assertThatThrownBy(() -> new AuditEntry(1, T0, null, "AUDITOR",
                    LedgerAction.CHAIN_VERIFIED, "s", "not-a-hash", Chain.GENESIS, Chain.GENESIS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("payloadHash");

            // Upper-case hex is refused too. The chain's comparisons are exact string
            // matches, so allowing two spellings of the same digest would mean an entry that
            // verifies in one place and not in another.
            String upperCased = Hashing.sha256Hex("x").toUpperCase(java.util.Locale.ROOT);
            assertThatThrownBy(() -> new AuditEntry(1, T0, null, "AUDITOR",
                    LedgerAction.CHAIN_VERIFIED, "s", Chain.GENESIS, upperCased, Chain.GENESIS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("prevHash");

            // And so is a digest of the wrong length.
            assertThatThrownBy(() -> new AuditEntry(1, T0, null, "AUDITOR",
                    LedgerAction.CHAIN_VERIFIED, "s", Chain.GENESIS, Chain.GENESIS, "abc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("entryHash");
        }

        @Test
        @DisplayName("an entry must name an actor role and a subject")
        void rejectsMissingContext() {
            assertThatThrownBy(() -> new AuditEntry(1, T0, null, " ",
                    LedgerAction.CHAIN_VERIFIED, "s", Chain.GENESIS, Chain.GENESIS, Chain.GENESIS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("actorRole");

            assertThatThrownBy(() -> new AuditEntry(1, T0, null, "AUDITOR",
                    LedgerAction.CHAIN_VERIFIED, "", Chain.GENESIS, Chain.GENESIS, Chain.GENESIS))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("subjectRef");
        }

        @Test
        @DisplayName("an entry knows whether it is self-consistent")
        void selfConsistency() {
            AuditEntry good = chainOf(1).getFirst();

            assertThat(good.isSelfConsistent()).isTrue();
            assertThat(good.recomputeHash()).isEqualTo(good.entryHash());
        }
    }
}
