package zw.ac.qvs.ledger.application;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.ChainVerification;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.shared.domain.CurrentActor;

/**
 * FR-09: hand an auditor a trail they can keep, and record that they did.
 *
 * <p>The export is recorded in the ledger before it is returned. That is not bureaucracy: an
 * audit system whose own use leaves no trace is one where a quiet export of the entire history
 * looks exactly like nothing happening at all. The acceptance criterion says the export itself
 * is logged, and this is where that happens.
 *
 * <p>The exported row count is written into the audit entry too, so a later dispute about what
 * a particular export contained can be settled against the ledger rather than against
 * somebody's copy of a CSV file.
 */
public class ExportTrail {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private final LedgerRepository ledger;
    private final AppendEntry append;
    private final Clock clock;

    public ExportTrail(LedgerRepository ledger, AppendEntry append, Clock clock) {
        this.ledger = ledger;
        this.append = append;
        this.clock = clock;
    }

    /**
     * An exported trail.
     *
     * @param filename suggested download name
     * @param content  the CSV body
     * @param rowCount how many entries it contains
     */
    public record Export(String filename, String content, int rowCount) {
    }

    /**
     * Exports a trail as CSV.
     *
     * @param actor   who is exporting
     * @param subject a credential serial, or null to use the date range
     * @param from    inclusive start, or null
     * @param to      exclusive end, or null
     * @return the export
     */
    public Export asCsv(CurrentActor actor, String subject, Instant from, Instant to) {
        List<AuditEntry> entries = select(subject, from, to);

        StringBuilder csv = new StringBuilder(entries.size() * 200 + 128);
        csv.append("seq,occurred_at,actor_role,action,subject_ref,payload_hash,prev_hash,"
                + "entry_hash,self_consistent\n");
        for (AuditEntry entry : entries) {
            csv.append(entry.seq()).append(',')
                    .append(entry.occurredAt()).append(',')
                    .append(quote(entry.actorRole())).append(',')
                    .append(entry.action().name()).append(',')
                    .append(quote(entry.subjectRef())).append(',')
                    .append(entry.payloadHash()).append(',')
                    .append(entry.prevHash()).append(',')
                    .append(entry.entryHash()).append(',')
                    .append(entry.isSelfConsistent())
                    .append('\n');
        }

        String filename = "qvs-audit-%s.csv".formatted(FILE_STAMP.format(Instant.now(clock)));

        // The row count goes into the ledger entry, so the acceptance criterion -- "row count
        // equals the ledger query count" -- can be checked after the fact rather than only at
        // the moment of export.
        append.record(actor.userId(), actor.role(), LedgerAction.TRAIL_EXPORTED,
                subject == null ? "range-export" : subject,
                "rows=" + entries.size() + "|file=" + filename);

        return new Export(filename, csv.toString(), entries.size());
    }

    /**
     * Records that somebody verified the chain, and what they found.
     *
     * @param actor  who ran the verification
     * @param result what they found
     */
    public void recordChainVerification(CurrentActor actor, ChainVerification result) {
        append.record(actor.userId(), actor.role(), LedgerAction.CHAIN_VERIFIED,
                "chain", result.summary());
    }

    private List<AuditEntry> select(String subject, Instant from, Instant to) {
        if (subject != null && !subject.isBlank()) {
            return ledger.forSubject(subject);
        }
        Instant start = from == null ? Instant.EPOCH : from;
        Instant end = to == null ? Instant.now(clock).plusSeconds(1) : to;
        return ledger.between(start, end);
    }

    /**
     * Quotes a CSV field.
     *
     * <p>A serial cannot contain a comma today, but a subject reference is free text and a
     * query fingerprint might be anything. An export that a spreadsheet silently misparses is
     * worse than no export, because nobody notices.
     */
    private static String quote(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
