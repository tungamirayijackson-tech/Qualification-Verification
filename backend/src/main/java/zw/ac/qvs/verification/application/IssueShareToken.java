package zw.ac.qvs.verification.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.verification.domain.CredentialUnderVerification;
import zw.ac.qvs.verification.domain.ShareToken;

/**
 * Mints the permission that lets a stranger check one credential.
 *
 * <p>The secret is returned to the caller exactly once and never stored. If the holder loses
 * it, the answer is to mint another and withdraw the old one — not to look the first one up,
 * because nothing in the system can.
 *
 * <p>Issuance is recorded in the ledger, and so is withdrawal. A holder who later asks "who
 * did I give access to, and when did I take it away" gets an answer that is evidence rather
 * than recollection.
 */
public class IssueShareToken {

    private final VerificationReadModel readModel;
    private final ShareTokenRepository tokens;
    private final AppendEntry ledger;
    private final Clock clock;
    private final int defaultTtlDays;
    private final int maxTtlDays;

    public IssueShareToken(
            VerificationReadModel readModel,
            ShareTokenRepository tokens,
            AppendEntry ledger,
            Clock clock,
            int defaultTtlDays,
            int maxTtlDays) {
        this.readModel = readModel;
        this.tokens = tokens;
        this.ledger = ledger;
        this.clock = clock;
        this.defaultTtlDays = defaultTtlDays;
        this.maxTtlDays = maxTtlDays;
    }

    /**
     * A minted token, as the console shows it to the registrar or holder.
     *
     * @param secret    the token, shown once
     * @param tokenId   its identity, so it can be withdrawn later
     * @param expiresAt when it lapses
     * @param serial    the credential it grants access to
     */
    public record Issued(String secret, UUID tokenId, Instant expiresAt, String serial) {
    }

    /**
     * Mints a token for a credential.
     *
     * @param serial  the credential
     * @param ttlDays requested lifetime in days, or null for the configured default
     * @param label   a note so the holder can tell their tokens apart
     * @param actorId who minted it
     * @param actorInstitution the institution the actor speaks for, or null for a role not
     *                         bound to one
     * @return the token, shown once
     */
    public Issued issue(String serial, Integer ttlDays, String label, UUID actorId,
            UUID actorInstitution) {
        CredentialUnderVerification credential = readModel.findBySerialForConsole(serial)
                .orElseThrow(() -> new IllegalArgumentException("no credential " + serial));

        // A share token is a public door onto one credential: anybody holding it can read the
        // award without an account. Minting one for another institution's credential would be
        // publishing their record on their behalf, so the caller must speak for the institution
        // that conferred it. Refused as "no credential", the same answer an unknown serial
        // gets, because serials are structured and guessable and this must not confirm one.
        if (actorInstitution != null && !actorInstitution.equals(credential.institutionId())) {
            throw new IllegalArgumentException("no credential " + serial);
        }

        int lifetime = ttlDays == null ? defaultTtlDays : ttlDays;
        if (lifetime <= 0) {
            throw new IllegalArgumentException("a share token must last at least a day");
        }
        if (lifetime > maxTtlDays) {
            // A token that never expires is a permanent leak waiting for someone to forward
            // an email, so the ceiling is enforced here rather than trusted to the caller.
            throw new IllegalArgumentException(
                    "a share token may last at most " + maxTtlDays + " days");
        }

        Instant now = Instant.now(clock);
        ShareToken.Minted minted = ShareToken.mint(
                credential.credentialId(), now, now.plus(Duration.ofDays(lifetime)), label);

        tokens.save(minted.record());

        // The ledger records that access was granted, and to what, but never the token itself
        // -- a ledger entry containing a working share link would defeat the point of hashing
        // the token in the first place.
        ledger.record(actorId, "REGISTRAR", LedgerAction.SHARE_TOKEN_ISSUED,
                credential.serial(), minted.record().id() + "|" + lifetime + "d");

        return new Issued(minted.secret(), minted.record().id(),
                minted.record().expiresAt(), credential.serial());
    }

    /**
     * Withdraws a token the holder no longer wants honoured.
     *
     * @param tokenId the token
     * @param serial  the credential it belongs to, for the audit entry
     * @param actorId who withdrew it
     */
    public void revoke(UUID tokenId, String serial, UUID actorId) {
        ShareToken token = tokens.findById(tokenId)
                .orElseThrow(() -> new IllegalArgumentException("no share token " + tokenId));

        tokens.save(token.revoked(Instant.now(clock)));

        ledger.record(actorId, "REGISTRAR", LedgerAction.SHARE_TOKEN_REVOKED,
                serial, tokenId.toString());
    }

    /**
     * Lists the tokens minted for a credential, so a holder can see and withdraw them.
     *
     * @param credentialId the credential
     * @return tokens, newest first
     */
    public List<ShareToken> listFor(UUID credentialId) {
        return tokens.findByCredential(credentialId);
    }
}
