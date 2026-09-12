package zw.ac.qvs.verification.application;

import zw.ac.qvs.verification.domain.SigningKey;

/**
 * Outbound port for making and checking signatures.
 *
 * <p>Deliberately narrow. The application layer knows that a credential is signed and that a
 * signature can be checked; it knows nothing about JOSE, curves or key encodings. That keeps
 * the cryptography in one replaceable adapter, which is where an assessor should be able to
 * look to audit it — rather than finding it smeared through use cases.
 *
 * <p>Signatures are <b>detached</b>: the serialised form carries the protected header and the
 * signature but not the payload. The payload is the canonical bytes already stored on the
 * credential row, so it is never duplicated, and there is no second copy to drift.
 */
public interface CredentialSigner {

    /**
     * Signs canonical bytes with the institution's current key.
     *
     * @param canonicalBytes the exact bytes to sign
     * @param kid            the key to sign with
     * @return detached JWS compact serialisation
     */
    String signDetached(byte[] canonicalBytes, String kid);

    /**
     * Checks a detached signature against a published public key.
     *
     * <p>Returns false rather than throwing on a malformed signature: an attacker controls the
     * signature string, so a parse failure is an expected input, not an exceptional one.
     *
     * @param canonicalBytes the bytes the signature should cover
     * @param detachedJws    the signature to check
     * @param key            the public key to check it against
     * @return true when the signature verifies
     */
    boolean verifyDetached(byte[] canonicalBytes, String detachedJws, SigningKey key);
}
