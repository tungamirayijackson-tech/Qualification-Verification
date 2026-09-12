package zw.ac.qvs.verification.application;

/**
 * Custody of the key this system signs its own verification reports with.
 *
 * <p>Separate from {@link KeyVault}, which holds institution keys, because they are used to say
 * different things and confusing them would be a misrepresentation rather than an
 * inconvenience. An institution key asserts "we conferred this award". This key asserts only
 * "we ran a check and this is what we found". Signing the second with the first would put words
 * in a university's mouth.
 *
 * <p>It is also why this key belongs to no institution and has no row in {@code signing_key}:
 * that table is a register of awarding bodies' public keys, and QVS is not an awarding body. A
 * row there would make this system appear in the list of institutions permitted to confer
 * qualifications, which it must never be.
 */
public interface ReportKeys {

    /**
     * The key reports are currently signed with.
     *
     * @return the key id, quoted in every report so rotation stays verifiable
     */
    String currentKid();

    /**
     * The public half, so a report's signature can be checked by someone who does not trust
     * this system — which is the only kind of person a signature is for.
     *
     * @return the public key as a JWK document
     */
    String publicJwk();
}
