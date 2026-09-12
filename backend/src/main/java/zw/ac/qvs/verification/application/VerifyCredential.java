package zw.ac.qvs.verification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.verification.domain.CheckOutcome;
import zw.ac.qvs.verification.domain.CredentialUnderVerification;
import zw.ac.qvs.verification.domain.VerificationCheck;
import zw.ac.qvs.verification.domain.Verdict;

/**
 * FR-06: what "authentic" actually resolves to.
 *
 * <p>The verdict is the conjunction of four independent checks, evaluated in a fixed order:
 * signature, issuer standing, revocation, ledger presence. Most implementations of this
 * requirement are a database lookup returning true, which asserts only that the server's own
 * table says so. Each check here asks a different question, and each can fail on its own.
 *
 * <p><b>Revocation is evaluated even when the signature fails</b>, and the ordering below is
 * chosen so that the FR-07 case comes out right: a revoked credential must report a valid
 * signature, because the institution really did confer the award before withdrawing it.
 *
 * <p>Every verification is written to the ledger, including the ones that fail. "Maintain an
 * auditable history of verification activities" means the failures too — a run of failed
 * checks against one serial is exactly the signal an institution would want to see.
 */
public class VerifyCredential {

    private final VerificationReadModel readModel;
    private final KeyVault keyVault;
    private final CredentialSigner signer;
    private final LedgerPresence ledgerPresence;
    private final AppendEntry ledger;
    private final VerificationLog log;
    private final Clock clock;

    public VerifyCredential(
            VerificationReadModel readModel,
            KeyVault keyVault,
            CredentialSigner signer,
            LedgerPresence ledgerPresence,
            AppendEntry ledger,
            VerificationLog log,
            Clock clock) {
        this.readModel = readModel;
        this.keyVault = keyVault;
        this.signer = signer;
        this.ledgerPresence = ledgerPresence;
        this.ledger = ledger;
        this.log = log;
        this.clock = clock;
    }

    /**
     * How a verifier reached the record, recorded for the audit trail.
     *
     * @param channel      WEB, API or QR
     * @param clientIpHash salted hash of the caller's address, or null when unavailable
     */
    public record Context(String channel, String clientIpHash) {

        /** A plain browser check with no address recorded. */
        public static Context web(String clientIpHash) {
            return new Context("WEB", clientIpHash);
        }
    }

    /**
     * The full outcome, including the parts a stranger is not shown.
     *
     * @param verdict         what the verifier is told
     * @param credential      the record checked, or null when nothing was found
     * @param ledgerSeq       sequence number of the entry recording this check, citable in a
     *                        dispute
     * @param ledgerEntryHash that entry's own hash. Carried so a report (FR-12) can tie itself
     *                        to a specific link in the chain rather than to a sequence number,
     *                        which says where the entry sits but nothing about what it says.
     * @param verifiedAt      when the check ran
     */
    public record Outcome(
            Verdict verdict,
            CredentialUnderVerification credential,
            long ledgerSeq,
            String ledgerEntryHash,
            Instant verifiedAt) {

        /** Whether there is a credential to describe in the response. */
        public boolean hasCredential() {
            return credential != null;
        }
    }

    /**
     * Verifies whatever a share token points at.
     *
     * @param rawShareToken the token as presented by the caller
     * @param context       how the request arrived
     * @return the outcome
     */
    public Outcome verify(String rawShareToken, Context context) {
        Instant now = Instant.now(clock);

        // The token is hashed before it is used as a lookup key, so the raw value never
        // reaches a query log or a database index.
        String tokenHash = rawShareToken == null || rawShareToken.isBlank()
                ? null
                : Hashing.sha256Hex(rawShareToken);

        Optional<CredentialUnderVerification> found = tokenHash == null
                ? Optional.empty()
                : readModel.findByShareTokenHash(tokenHash);

        if (found.isEmpty() || !found.get().shareTokenUsable()) {
            // Unknown, expired and withdrawn tokens are answered identically, so the endpoint
            // cannot be used to discover which of the three a given token is.
            return record(Verdict.NotFound.unknownToken(), null, context, now);
        }

        CredentialUnderVerification credential = found.get();
        Map<VerificationCheck, CheckOutcome> checks = new EnumMap<>(VerificationCheck.class);

        boolean signatureOk = checkSignature(credential);
        checks.put(VerificationCheck.SIGNATURE, outcome(signatureOk));

        boolean standingOk = credential.issuerHadStandingOnAwardDate()
                && keyWasValidOnAwardDate(credential);
        checks.put(VerificationCheck.ISSUER_STANDING, outcome(standingOk));

        boolean notRevoked = !credential.revoked();
        checks.put(VerificationCheck.REVOCATION, outcome(notRevoked));

        boolean inLedger = ledgerPresence.wasIssuanceRecorded(credential.serial());
        checks.put(VerificationCheck.LEDGER_PRESENCE, outcome(inLedger));

        Verdict verdict = decide(credential, checks, signatureOk, standingOk, notRevoked, inLedger);
        return record(verdict, credential, context, now);
    }

    /**
     * Turns four booleans into one of three answers.
     *
     * <p>The revoked case is checked <em>before</em> the integrity cases, because a revoked
     * credential with a perfectly good signature is a normal, expected state and the verifier
     * deserves to be told so — with the reason. An integrity failure is a different situation
     * altogether and collapses to not-found (see {@link Verdict}).
     */
    private Verdict decide(
            CredentialUnderVerification credential,
            Map<VerificationCheck, CheckOutcome> checks,
            boolean signatureOk,
            boolean standingOk,
            boolean notRevoked,
            boolean inLedger) {

        if (signatureOk && standingOk && inLedger && !notRevoked) {
            return new Verdict.Revoked(
                    credential.revokedReason() == null ? "OTHER" : credential.revokedReason(),
                    credential.revokedAt() == null ? Instant.EPOCH : credential.revokedAt(),
                    checks);
        }
        if (!signatureOk) {
            return new Verdict.NotFound(VerificationCheck.SIGNATURE);
        }
        if (!standingOk) {
            return new Verdict.NotFound(VerificationCheck.ISSUER_STANDING);
        }
        if (!inLedger) {
            return new Verdict.NotFound(VerificationCheck.LEDGER_PRESENCE);
        }
        if (!notRevoked) {
            return new Verdict.NotFound(VerificationCheck.REVOCATION);
        }
        return new Verdict.Valid(checks);
    }

    private boolean checkSignature(CredentialUnderVerification credential) {
        return keyVault.findByKid(credential.keyId())
                .map(key -> signer.verifyDetached(
                        credential.payloadCanonical().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        credential.detachedJws(),
                        key))
                .orElse(false);
    }

    private boolean keyWasValidOnAwardDate(CredentialUnderVerification credential) {
        // Decision 05-A: resolve the key that was valid then, not the one that is current.
        return keyVault.findByKid(credential.keyId())
                .filter(key -> key.institutionId().equals(credential.institutionId()))
                .map(key -> key.wasValidOn(credential.awardedOn()))
                .orElse(false);
    }

    private static CheckOutcome outcome(boolean passed) {
        return passed ? CheckOutcome.PASS : CheckOutcome.FAIL;
    }

    private Outcome record(
            Verdict verdict,
            CredentialUnderVerification credential,
            Context context,
            Instant now) {

        String subject = credential == null ? "unknown-token" : credential.serial();
        var entry = ledger.recordAnonymous(LedgerAction.VERIFICATION_RUN, subject,
                verdict.name() + "|" + context.channel());

        log.record(new VerificationLog.Record(
                credential == null ? null : credential.credentialId(),
                context.channel(),
                context.clientIpHash(),
                verdict.name(),
                failedCheckOf(verdict),
                now,
                entry.seq()));

        return new Outcome(verdict, credential, entry.seq(), entry.entryHash(), now);
    }

    private static String failedCheckOf(Verdict verdict) {
        if (verdict instanceof Verdict.NotFound notFound && notFound.failedCheck() != null) {
            return notFound.failedCheck().name();
        }
        return null;
    }
}
