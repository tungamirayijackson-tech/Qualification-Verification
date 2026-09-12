package zw.ac.qvs.verification.application;

import java.util.Optional;
import zw.ac.qvs.verification.domain.CredentialUnderVerification;

/**
 * Outbound port for reading a credential as the verification path needs to see it.
 *
 * <p>Lookup is by the <em>hash</em> of a share token. There is deliberately no method here
 * that finds a credential by serial, by holder or by anything else a stranger could guess or
 * enumerate: Decision 09-A says a verifier can only check a credential whose token the holder
 * gave them, and the port is shaped so that no other query is available to write by accident.
 */
public interface VerificationReadModel {

    /**
     * Finds the credential a share token points at.
     *
     * @param shareTokenHash SHA-256 of the presented token
     * @return the credential and its token state, or empty when the token is unknown
     */
    Optional<CredentialUnderVerification> findByShareTokenHash(String shareTokenHash);

    /**
     * Finds a credential by serial, for the authenticated console only.
     *
     * <p>Never reachable from the public path — the public controller has no route that
     * accepts a serial.
     *
     * @param serial the credential serial
     * @return the credential, or empty
     */
    Optional<CredentialUnderVerification> findBySerialForConsole(String serial);
}
