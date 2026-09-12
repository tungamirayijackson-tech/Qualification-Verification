package zw.ac.qvs.credential.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import zw.ac.qvs.credential.domain.NationalId;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.CredentialStatus;
import zw.ac.qvs.credential.domain.Holder;
import zw.ac.qvs.credential.domain.Institution;
import zw.ac.qvs.credential.domain.Qualification;
import zw.ac.qvs.credential.domain.Serial;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.domain.LedgerAction;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.verification.application.CredentialSigner;
import zw.ac.qvs.verification.application.KeyVault;
import zw.ac.qvs.verification.domain.CredentialClaims;
import zw.ac.qvs.verification.domain.SigningKey;

/**
 * FR-01: record a qualification against an accredited institution, and sign it.
 *
 * <p>The order of operations matters and is worth reading closely. Every refusal happens
 * before anything is written, so a rejected registration leaves no trace in the register. The
 * signature is made before the row is saved, so a credential can never exist unsigned — there
 * is no window, not even a transactional one, in which the register holds an unvouched-for
 * award.
 *
 * <p>The whole thing runs in the caller's transaction alongside the ledger append, so the
 * issuance entry and the credential commit together or not at all.
 */
public class RegisterCredential {

    private final InstitutionRepository institutions;
    private final QualificationRepository qualifications;
    private final HolderRepository holders;
    private final CredentialRepository credentials;
    private final KeyVault keyVault;
    private final CredentialSigner signer;
    private final AppendEntry ledger;
    private final Clock clock;
    private final String nationalIdSalt;

    public RegisterCredential(
            InstitutionRepository institutions,
            QualificationRepository qualifications,
            HolderRepository holders,
            CredentialRepository credentials,
            KeyVault keyVault,
            CredentialSigner signer,
            AppendEntry ledger,
            Clock clock,
            String nationalIdSalt) {
        this.institutions = institutions;
        this.qualifications = qualifications;
        this.holders = holders;
        this.credentials = credentials;
        this.keyVault = keyVault;
        this.signer = signer;
        this.ledger = ledger;
        this.clock = clock;
        this.nationalIdSalt = nationalIdSalt;
    }

    /**
     * What a registrar submits.
     *
     * @param institutionId    the awarding body
     * @param qualificationId  what was conferred
     * @param holderNationalId the holder's national ID, hashed immediately and never stored
     * @param holderName       the holder's full name as conferred
     * @param holderDateOfBirth optional, to disambiguate identical names
     * @param awardedOn        the date of conferral
     * @param actorId          the registrar recording it
     */
    public record Command(
            UUID institutionId,
            UUID qualificationId,
            String holderNationalId,
            String holderName,
            LocalDate holderDateOfBirth,
            LocalDate awardedOn,
            UUID actorId) {
    }

    /**
     * Registers and signs one credential.
     *
     * @param command the registration
     * @return the signed credential
     */
    public Credential register(Command command) {
        Institution institution = institutions.findById(command.institutionId())
                .orElseThrow(() -> new RegistrationRejected(
                        RegistrationRejected.Reason.UNKNOWN_REFERENCE,
                        "no institution " + command.institutionId()));

        Qualification qualification = qualifications.findById(command.qualificationId())
                .orElseThrow(() -> new RegistrationRejected(
                        RegistrationRejected.Reason.UNKNOWN_REFERENCE,
                        "no qualification " + command.qualificationId()));

        LocalDate awardedOn = command.awardedOn();
        LocalDate today = LocalDate.now(clock);

        checkAwardDate(awardedOn, today);
        checkQualificationBelongsToInstitution(qualification, institution);
        checkQualificationStillOffered(qualification);
        checkInstitutionStanding(institution, awardedOn);

        SigningKey key = keyVault.keyValidOn(institution.id(), awardedOn)
                .or(() -> keyVault.currentKeyFor(institution.id()))
                .orElseThrow(() -> new RegistrationRejected(
                        RegistrationRejected.Reason.INSTITUTION_HAS_NO_KEY,
                        institution.name() + " has no signing key valid on " + awardedOn));

        Holder holder = findOrCreateHolder(command);
        Serial serial = nextSerial(institution, awardedOn);

        CredentialClaims claims = new CredentialClaims(
                serial.value(),
                issuerUrn(institution),
                holder.nationalIdHash(),
                holder.displayName(),
                qualification.title(),
                qualification.nqfLevel().value(),
                qualification.credits(),
                awardedOn,
                key.kid());

        // Sign before persisting. There is no ordering here in which an unsigned credential
        // is briefly visible, even to a query inside the same transaction.
        String jws = signer.signDetached(claims.canonicalBytes(), key.kid());

        Credential credential = credentials.insert(new Credential(
                UUID.randomUUID(), serial, qualification.id(), holder.id(), awardedOn,
                key.kid(), jws, claims.canonicalJson(), CredentialStatus.ISSUED,
                null, null, null, null, Instant.now(clock)));

        ledger.record(command.actorId(), "REGISTRAR", LedgerAction.CREDENTIAL_ISSUED,
                serial.value(), claims.canonicalJson());

        return credential;
    }

    private void checkAwardDate(LocalDate awardedOn, LocalDate today) {
        if (awardedOn.isAfter(today)) {
            throw new RegistrationRejected(
                    RegistrationRejected.Reason.IMPOSSIBLE_AWARD_DATE,
                    "an award cannot be conferred in the future: " + awardedOn);
        }
    }

    private void checkQualificationBelongsToInstitution(
            Qualification qualification, Institution institution) {
        if (!qualification.institutionId().equals(institution.id())) {
            // Without this an accredited institution could sign an award for a qualification
            // that belongs to someone else, and the signature would verify perfectly.
            throw new RegistrationRejected(
                    RegistrationRejected.Reason.QUALIFICATION_NOT_OFFERED_HERE,
                    qualification.title() + " is not offered by " + institution.name());
        }
    }

    private void checkQualificationStillOffered(Qualification qualification) {
        if (!qualification.acceptsNewAwards()) {
            throw new RegistrationRejected(
                    RegistrationRejected.Reason.QUALIFICATION_PHASED_OUT,
                    qualification.title() + " has been phased out and accepts no new awards");
        }
    }

    private void checkInstitutionStanding(Institution institution, LocalDate awardedOn) {
        // Standing is judged on the award date, not today. Backdating an award to a period
        // when the institution was accredited is legitimate; awarding under a lapsed
        // accreditation is not.
        if (!institution.wasAccreditedOn(awardedOn)) {
            throw new RegistrationRejected(
                    RegistrationRejected.Reason.INSTITUTION_NOT_ACCREDITED,
                    institution.name() + " was not accredited on " + awardedOn
                            + " (accreditation ended " + institution.accreditedUntil() + ")");
        }
    }

    private Holder findOrCreateHolder(Command command) {
        // The national ID is hashed here, at the edge of the domain, and the plaintext is
        // never passed further in. Salted, so that the hash cannot be attacked with a
        // precomputed table of every syntactically valid Zimbabwean ID number -- a space
        // small enough to enumerate exhaustively without one.
        String idHash = Hashing.sha256Hex(
                nationalIdSalt + NationalId.parse(command.holderNationalId()).canonical());

        return holders.findByNationalIdHash(idHash)
                .orElseGet(() -> holders.insert(new Holder(
                        UUID.randomUUID(),
                        "hld_" + idHash.substring(0, 16),
                        idHash,
                        command.holderName(),
                        command.holderDateOfBirth())));
    }

    private Serial nextSerial(Institution institution, LocalDate awardedOn) {
        String code = institutionCode(institution);
        int year = awardedOn.getYear();
        return Serial.of(institution.country(), code, year,
                credentials.reserveSerialSequence(code, year));
    }

    /**
     * Derives the short institution code embedded in a serial.
     *
     * <p>Taken from the provider registration number rather than the name: provider numbers
     * are unique by database constraint and stable, whereas an institution can rename itself
     * and a serial must stay meaningful for decades.
     */
    private static String institutionCode(Institution institution) {
        String cleaned = institution.providerNumber()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
        if (cleaned.length() < 2) {
            throw new IllegalStateException(
                    "provider number " + institution.providerNumber()
                            + " does not yield a usable serial code");
        }
        return cleaned.substring(0, Math.min(8, cleaned.length()));
    }

    private static String issuerUrn(Institution institution) {
        return "urn:qvs:inst:" + institution.id();
    }
}
