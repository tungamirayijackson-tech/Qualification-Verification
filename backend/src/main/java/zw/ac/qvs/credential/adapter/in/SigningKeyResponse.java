package zw.ac.qvs.credential.adapter.in;

import java.time.LocalDate;
import zw.ac.qvs.credential.application.IssueSigningKey;

/**
 * A newly issued signing key, as the console sees it.
 *
 * <p>The public half only. The private key never leaves the vault -- {@code KeyVault} is written
 * so that nothing above it can even ask for one -- and an administrator has no use for it: the
 * system signs on the institution's behalf, and a key an operator could copy out is a key that
 * can be copied out.
 *
 * @param kid        the key identifier, quoted in every signature it makes
 * @param validFrom  first day it is used to sign
 * @param publicJwk  the public half, so a verifier can be pointed at it
 * @param supersedes the key it replaced, or null when this is the institution's first
 */
public record SigningKeyResponse(
        String kid, LocalDate validFrom, String publicJwk, String supersedes) {

    static SigningKeyResponse from(IssueSigningKey.Issued issued) {
        return new SigningKeyResponse(
                issued.key().kid(),
                issued.key().validFrom(),
                issued.key().publicJwk(),
                issued.supersedes());
    }
}
