package zw.ac.qvs.verification.application;

import zw.ac.qvs.verification.domain.CredentialUnderVerification;
import zw.ac.qvs.verification.domain.SignedVerificationReport;
import zw.ac.qvs.verification.domain.Verdict;
import zw.ac.qvs.verification.domain.VerificationReport;

/**
 * FR-12: produce a signed verification report a verifier can keep.
 *
 * <p>The report is produced by <b>running a verification</b>, not by formatting an earlier one.
 * That is the significant decision in this class and it is worth being explicit about, because
 * the alternative looks more efficient and is wrong. A report generated from a cached result
 * would state a verdict as of a moment in the past while carrying today's date, and would keep
 * saying "valid" about a credential revoked an hour ago. Every report therefore describes a
 * check that actually happened, gets its own entry in the ledger, and cites that entry.
 *
 * <p>Downloading a report is consequently an audited event in its own right. That is intended:
 * an institution disputing a report can find the exact check it came from, and a pattern of one
 * address pulling reports for two hundred credentials is visible to an auditor.
 */
public class ProduceVerificationReport {

    private final VerifyCredential verifyCredential;
    private final CredentialSigner signer;
    private final ReportKeys reportKeys;
    private final ReportRenderer renderer;

    public ProduceVerificationReport(
            VerifyCredential verifyCredential,
            CredentialSigner signer,
            ReportKeys reportKeys,
            ReportRenderer renderer) {
        this.verifyCredential = verifyCredential;
        this.signer = signer;
        this.reportKeys = reportKeys;
        this.renderer = renderer;
    }

    /**
     * A rendered report, ready to be sent.
     *
     * @param document  the rendered bytes
     * @param mediaType what they are
     * @param filename  a name that identifies the check it records
     * @param ledgerSeq the ledger entry this report cites
     */
    public record Rendered(byte[] document, String mediaType, String filename, long ledgerSeq) {

        public Rendered {
            document = document.clone();
        }

        @Override
        public byte[] document() {
            return document.clone();
        }
    }

    /**
     * Runs a verification and returns a signed report of it.
     *
     * @param rawShareToken the token as presented by the caller
     * @param context       how the request arrived
     * @return the rendered report
     */
    public Rendered produce(String rawShareToken, VerifyCredential.Context context) {
        VerifyCredential.Outcome outcome = verifyCredential.verify(rawShareToken, context);
        SignedVerificationReport signed = sign(statementFor(outcome));

        return new Rendered(
                renderer.render(signed),
                renderer.mediaType(),
                filenameFor(outcome),
                outcome.ledgerSeq());
    }

    private SignedVerificationReport sign(VerificationReport report) {
        String kid = reportKeys.currentKid();
        return new SignedVerificationReport(
                report, kid, signer.signDetached(report.canonicalBytes(), kid));
    }

    /**
     * Builds the statement from an outcome.
     *
     * <p>The not-found branch returns early and fills in nothing but the four facts that always
     * exist. It is written as a separate path rather than as a series of null checks so that
     * "the not-found report discloses nothing" is a property of the shape of this method, not
     * something to be re-established every time a field is added.
     */
    private static VerificationReport statementFor(VerifyCredential.Outcome outcome) {
        Verdict verdict = outcome.verdict();

        if (!outcome.hasCredential()) {
            return new VerificationReport(null, verdict.name(), outcome.verifiedAt(),
                    outcome.ledgerSeq(), outcome.ledgerEntryHash(),
                    null, null, null, null, null, null, null, null);
        }

        CredentialUnderVerification credential = outcome.credential();
        String revokedReason = verdict instanceof Verdict.Revoked r ? r.reason() : null;
        var revokedAt = verdict instanceof Verdict.Revoked r ? r.revokedAt() : null;

        return new VerificationReport(
                credential.serial(),
                verdict.name(),
                outcome.verifiedAt(),
                outcome.ledgerSeq(),
                outcome.ledgerEntryHash(),
                credential.keyId(),
                credential.institutionName(),
                credential.qualificationTitle(),
                credential.nqfLevel(),
                credential.awardedOn(),
                credential.holderInitials(),
                revokedReason,
                revokedAt);
    }

    /**
     * Names the file after the ledger entry rather than the credential.
     *
     * <p>A verifier who checks several credentials ends up with several of these in one folder,
     * and the sequence number is what distinguishes two checks of the same credential a month
     * apart. It also avoids putting a serial — which identifies a real person's award — into a
     * filename that will be mailed around and backed up.
     */
    private String filenameFor(VerifyCredential.Outcome outcome) {
        return "qvs-verification-" + outcome.ledgerSeq() + "." + renderer.fileExtension();
    }
}
