package zw.ac.qvs.credential.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zw.ac.qvs.credential.domain.Institution;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.verification.application.KeyVault;
import zw.ac.qvs.verification.domain.SigningKey;

/**
 * Gives an institution the key it signs with, or replaces the one it has.
 *
 * <p>Both at once, deliberately: there is no separate "first key" operation, because from the
 * register's point of view the first key and the fourth are the same act — the institution
 * starts signing with something new from a given date. {@link KeyVault#rotate} already closes
 * the previous key's validity window the day before the new one opens, so there is never a date
 * with two current keys and never a gap with none.
 *
 * <p><b>Old credentials keep verifying.</b> Verification resolves the key that was valid on the
 * <em>award date</em>, not the key that is current, and every public key an institution has ever
 * used stays in the register. Rotating is therefore safe in the way that matters: it changes
 * what gets signed tomorrow and nothing about what was signed last year.
 *
 * <p>What it cannot do is undo. The vault refuses to overwrite existing private material, so a
 * key issued by mistake cannot be deleted, only superseded — which is why this is an
 * administrator's act, is audited, and is not offered as a casual button.
 */
public class IssueSigningKey {

    private static final Logger log = LoggerFactory.getLogger(IssueSigningKey.class);

    private final InstitutionRepository institutions;
    private final KeyVault keyVault;
    private final AppendEntry ledger;
    private final Clock clock;

    public IssueSigningKey(
            InstitutionRepository institutions,
            KeyVault keyVault,
            AppendEntry ledger,
            Clock clock) {
        this.institutions = institutions;
        this.keyVault = keyVault;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * The outcome, so a caller can tell an institution's first key from a replacement.
     *
     * @param key        the new key
     * @param supersedes the key it replaced, or null when this is the institution's first
     */
    public record Issued(SigningKey key, String supersedes) {
    }

    /**
     * Issues a key and makes it the institution's current one.
     *
     * @param institutionId which institution
     * @param from          first day the new key is valid; today when null
     * @param actorId       the administrator doing it, recorded in the ledger
     * @return the new key, and the one it replaced
     * @throws RegistrationRejected when the institution is unknown
     */
    public Issued issue(UUID institutionId, LocalDate from, UUID actorId) {
        Institution institution = institutions.findById(institutionId)
                .orElseThrow(() -> new RegistrationRejected(
                        RegistrationRejected.Reason.UNKNOWN_REFERENCE,
                        "No institution with id " + institutionId + " is registered."));

        String previous = institution.activeKeyId();
        LocalDate effective = from != null ? from : LocalDate.now(clock);

        // Rotation closes the outgoing key's window the day before the new one opens, so a
        // replacement starting on or before the current key's first day would give that key a
        // validity window that ends before it began. The database refuses it -- correctly --
        // and the refusal used to surface as a 500 with a constraint name in the log. Said
        // here instead, in terms of the decision the administrator actually has to make.
        keyVault.currentKeyFor(institutionId).ifPresent(current -> {
            if (!effective.isAfter(current.validFrom())) {
                throw new RegistrationRejected(
                        RegistrationRejected.Reason.IMPOSSIBLE_AWARD_DATE,
                        institution.name() + " has been signing with " + current.kid()
                                + " since " + current.validFrom() + ". A replacement key must "
                                + "start after that date -- the earliest is "
                                + current.validFrom().plusDays(1) + ".");
            }
        });

        SigningKey key = keyVault.rotate(institutionId, effective);

        // The vault owns the keys; the institution row owns the pointer to the current one.
        // Both have to move, and this is the only place that knows about both.
        institutions.save(new Institution(
                institution.id(),
                institution.name(),
                institution.country(),
                institution.providerNumber(),
                institution.accreditedUntil(),
                key.kid()));

        log.info("institution {} now signs with {} (previously {})",
                institution.name(), key.kid(), previous == null ? "no key" : previous);

        ledger.record(actorId, "ADMIN", LedgerAction.KEY_ROTATED, institutionId.toString(),
                key.kid() + "|supersedes=" + (previous == null ? "none" : previous)
                        + "|from=" + effective);

        return new Issued(key, previous);
    }
}
