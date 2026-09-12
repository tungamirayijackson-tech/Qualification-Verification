package zw.ac.qvs.credential.adapter.in;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import zw.ac.qvs.credential.domain.RevocationReason;

/**
 * A withdrawal.
 *
 * @param reason a closed-set reason code, so a verifier can act on it without reading prose
 * @param note   free text for the audit entry; required when the reason is OTHER
 */
public record RevokeCredentialRequest(
        @NotNull(message = "a revocation reason is required")
        RevocationReason reason,

        @Size(max = 500, message = "a note may be at most 500 characters")
        String note) {
}
