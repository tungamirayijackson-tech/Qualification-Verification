package zw.ac.qvs.credential.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.RevocationReason;
import zw.ac.qvs.credential.domain.Serial;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;

/**
 * FR-07: withdraw a credential, with a reason and an actor.
 *
 * <p>The signature is left completely untouched. That is the whole substance of this
 * requirement and the part most implementations get wrong by deleting the record or
 * invalidating the signature: the institution <em>did</em> confer the award, and it later
 * withdrew it. Both are facts, they happened at different times, and a verifier is entitled to
 * see both. A revoked credential therefore verifies with a valid signature and a revoked
 * verdict, which is exactly what the demo shows and what the viva should be able to explain.
 */
public class RevokeCredential {

    private final CredentialRepository credentials;
    private final AppendEntry ledger;
    private final Clock clock;

    private final QualificationRepository qualifications;

    public RevokeCredential(CredentialRepository credentials,
            QualificationRepository qualifications, AppendEntry ledger, Clock clock) {
        this.credentials = credentials;
        this.qualifications = qualifications;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * What a registrar submits.
     *
     * @param serial  the credential to withdraw
     * @param reason  why
     * @param note    free text for the audit entry, required when the reason is OTHER
     * @param actorId who is withdrawing it
     * @param actorInstitution the institution the actor speaks for, or null for a role that is
     *                         not bound to one
     */
    public record Command(Serial serial, RevocationReason reason, String note, UUID actorId,
            UUID actorInstitution) {
    }

    /**
     * Withdraws a credential.
     *
     * @param command the revocation
     * @return the revoked credential
     */
    public Credential revoke(Command command) {
        Credential credential = credentials.findBySerial(command.serial())
                .orElseThrow(() -> notInReach(command.serial()));

        requireWithinScope(credential, command.actorInstitution());

        if (credential.isRevoked()) {
            // Idempotent would be tempting here, but silently accepting a second revocation
            // would let the audit trail show two withdrawals of one award, with two different
            // actors and reasons, and no way to tell which one counted.
            throw new IllegalStateException(
                    "credential " + command.serial() + " is already revoked");
        }
        if (command.reason() == RevocationReason.OTHER
                && (command.note() == null || command.note().isBlank())) {
            throw new IllegalArgumentException(
                    "a revocation reason of OTHER must be explained in a note");
        }

        Instant now = Instant.now(clock);
        Credential revoked = credentials.save(
                credential.revoked(command.reason(), now, command.actorId()));

        ledger.record(command.actorId(), "REGISTRAR", LedgerAction.CREDENTIAL_REVOKED,
                command.serial().value(),
                command.reason().name() + "|" + (command.note() == null ? "" : command.note()));

        return revoked;
    }

    /**
     * Refuses a credential that belongs to somebody else's institution.
     *
     * <p>Serials are structured and therefore guessable — {@code ZW-PR0142-2026-000001} names
     * the institution and counts from one. Without this, a registrar who knew the shape could
     * withdraw another university's award, and the ledger would record that university as
     * having revoked it. The authorisation rule said REGISTRAR, and every registrar passed it;
     * "which registrar" was never asked.
     *
     * <p>The refusal is deliberately the <b>same</b> failure as an unknown serial, with the
     * same message. Distinguishing them would answer "does this serial exist?" for anybody with
     * an account, which is the question a guessable identifier must not answer.
     */
    private void requireWithinScope(Credential credential, UUID actorInstitution) {
        if (actorInstitution == null) {
            return;
        }
        UUID owner = qualifications.findById(credential.qualificationId())
                .map(qualification -> qualification.institutionId())
                .orElseThrow(() -> notInReach(credential.serial()));

        if (!owner.equals(actorInstitution)) {
            throw notInReach(credential.serial());
        }
    }

    private static IllegalArgumentException notInReach(Serial serial) {
        return new IllegalArgumentException("no credential " + serial);
    }
}
