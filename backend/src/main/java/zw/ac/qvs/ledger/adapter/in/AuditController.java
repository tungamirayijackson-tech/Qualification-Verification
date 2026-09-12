package zw.ac.qvs.ledger.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.ac.qvs.ledger.application.ExportTrail;
import zw.ac.qvs.ledger.application.LedgerRepository;
import zw.ac.qvs.ledger.application.VerifyChain;
import zw.ac.qvs.ledger.domain.AuditEntry;
import zw.ac.qvs.ledger.domain.ChainVerification;
import zw.ac.qvs.shared.adapter.in.ActorResolver;

/**
 * The auditor's endpoints.
 *
 * <p>Read-only by role, and that is enforced rather than assumed: an auditor holds no authority
 * that permits any write anywhere in the system. An auditor who could append to the ledger
 * would be able to cover their own tracks, which would make every audit they signed worthless.
 *
 * <p>Note that exporting a trail is itself recorded (FR-09). An audit system whose own use
 * leaves no trace is one where a quiet, unexplained export of everything looks exactly like
 * nothing at all.
 */
@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit", description = "Query the ledger, export a trail, verify the chain")
public class AuditController {

    private final LedgerRepository ledger;
    private final VerifyChain verifyChain;
    private final ExportTrail exportTrail;
    private final ActorResolver actors;
    private final Clock clock;

    public AuditController(
            LedgerRepository ledger,
            VerifyChain verifyChain,
            ExportTrail exportTrail,
            ActorResolver actors,
            Clock clock) {
        this.ledger = ledger;
        this.verifyChain = verifyChain;
        this.exportTrail = exportTrail;
        this.actors = actors;
        this.clock = clock;
    }

    /**
     * One ledger entry, as an auditor sees it.
     *
     * @param seq         sequence number
     * @param occurredAt  when
     * @param actorRole   who, by role
     * @param action      what
     * @param subjectRef  to what
     * @param payloadHash hash of the detail
     * @param prevHash    the preceding entry's hash
     * @param entryHash   this entry's hash
     * @param intact      whether this entry still hashes to its stored value
     */
    public record EntryView(
            long seq,
            Instant occurredAt,
            String actorRole,
            String action,
            String subjectRef,
            String payloadHash,
            String prevHash,
            String entryHash,
            boolean intact) {

        static EntryView from(AuditEntry entry) {
            return new EntryView(entry.seq(), entry.occurredAt(), entry.actorRole(),
                    entry.action().name(), entry.subjectRef(), entry.payloadHash(),
                    entry.prevHash(), entry.entryHash(), entry.isSelfConsistent());
        }
    }

    /**
     * The result of recomputing the chain.
     *
     * @param intact       whether the whole history recomputed
     * @param headSequence the sequence number at the head
     * @param brokenAtSeq  where it stopped being trustworthy, or null
     * @param breakKind    what sort of break, or null
     * @param detail       a sentence an auditor can put in a report
     */
    public record ChainVerificationView(
            boolean intact, long headSequence, Long brokenAtSeq, String breakKind, String detail) {
    }

    /**
     * FR-08: query the ledger.
     *
     * @param subject a credential serial, or null for everything
     * @return entries, newest first
     */
    @GetMapping
    @PreAuthorize("hasRole('AUDITOR')")
    @Operation(summary = "Query the ledger by subject")
    public List<EntryView> query(@RequestParam(required = false) String subject) {
        List<AuditEntry> entries = subject == null || subject.isBlank()
                ? ledger.range(Math.max(1, ledger.headSequence() - 200), ledger.headSequence())
                : ledger.forSubject(subject);

        return entries.stream().map(EntryView::from).toList();
    }

    /**
     * FR-08: recompute the chain and report the first break.
     *
     * <p>This is the operation the demonstration runs after editing a ledger row directly with
     * superuser privileges. It reports the exact sequence number where the history stops being
     * trustworthy, which is the difference between "something may be wrong" and an actionable
     * finding.
     *
     * @return the verification result
     */
    @PostMapping("/verify-chain")
    @PreAuthorize("hasRole('AUDITOR')")
    @Transactional
    @Operation(summary = "Recompute the hash chain",
            description = "Returns the first break with its sequence number, or confirms the "
                    + "chain is intact.")
    public ChainVerificationView verifyChain() {
        long head = ledger.headSequence();
        var broken = verifyChain.verifyAll();

        var result = new ChainVerification(Instant.now(clock), head, head, broken.orElse(null));

        // Recording the verification is the point: an institution can later show not just that
        // the chain was intact, but that somebody checked, and when.
        exportTrail.recordChainVerification(actors.requireAuthenticated(), result);

        return new ChainVerificationView(
                result.isIntact(),
                head,
                broken.map(b -> b.atSeq()).orElse(null),
                broken.map(b -> b.kind().name()).orElse(null),
                result.summary());
    }

    /**
     * FR-09: export a trail as CSV.
     *
     * @param subject a credential serial, or null for a date range
     * @param from    inclusive start of a date range
     * @param to      exclusive end of a date range
     * @return the CSV
     */
    @GetMapping(value = "/export", produces = "text/csv")
    @PreAuthorize("hasRole('AUDITOR')")
    @Transactional
    @Operation(summary = "Export an audit trail as CSV",
            description = "The export itself is recorded in the ledger.")
    public ResponseEntity<String> export(
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {

        ExportTrail.Export export = exportTrail.asCsv(
                actors.requireAuthenticated(), subject, from, to);

        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + export.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(export.content());
    }
}
