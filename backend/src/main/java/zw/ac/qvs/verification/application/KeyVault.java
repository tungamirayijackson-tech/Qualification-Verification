package zw.ac.qvs.verification.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.verification.domain.SigningKey;

/**
 * Outbound port for key custody.
 *
 * <p>The interface is written as though a hardware security module were behind it, because one
 * day it should be: nothing here ever returns a private key. Signing happens inside the
 * implementation, and the caller receives a signature.
 *
 * <p>The report should be honest about what actually sits behind this in the submitted system
 * — a local encrypted keystore, not an HSM — and about what that means: an attacker with the
 * host and the keystore passphrase can forge credentials that verify. §18 lists it as a named
 * limit with the mitigation that every issuance is attributed in the ledger.
 */
public interface KeyVault {

    /**
     * Finds a key by its identifier.
     *
     * @param kid the key id quoted in a signature
     * @return the key, or empty when unknown
     */
    Optional<SigningKey> findByKid(String kid);

    /**
     * Finds the key an institution is currently signing with.
     *
     * @param institutionId the institution
     * @return the current key, or empty when the institution has not been onboarded
     */
    Optional<SigningKey> currentKeyFor(UUID institutionId);

    /**
     * Finds the key an institution was signing with on a given date.
     *
     * <p>This, not {@link #currentKeyFor}, is what verification uses.
     *
     * @param institutionId the institution
     * @param on            the date in question, normally an award date
     * @return the key valid then, or empty
     */
    Optional<SigningKey> keyValidOn(UUID institutionId, LocalDate on);

    /**
     * Every public key an institution has ever used, for publication at a JWKS endpoint.
     *
     * @param institutionId the institution
     * @return keys, newest first
     */
    List<SigningKey> allKeysFor(UUID institutionId);

    /**
     * Generates a new key pair, closes the previous key's validity window, and makes the new
     * key current from the given date.
     *
     * @param institutionId the institution
     * @param from          first day the new key is valid
     * @return the new public key record
     */
    SigningKey rotate(UUID institutionId, LocalDate from);
}
