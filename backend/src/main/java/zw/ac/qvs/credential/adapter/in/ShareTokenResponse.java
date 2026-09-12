package zw.ac.qvs.credential.adapter.in;

import java.time.Instant;
import java.util.UUID;
import zw.ac.qvs.verification.application.IssueShareToken;

/**
 * A minted share token.
 *
 * <p>{@code token} is shown exactly once. Nothing in the system can retrieve it afterwards,
 * because only its hash was stored — so the console has to make clear that this is the moment
 * to copy it, and the answer to losing it is to mint another and withdraw this one. That is
 * not a limitation to apologise for; it is the property that makes a database disclosure
 * useless to whoever obtains it.
 *
 * @param token     the secret, shown once
 * @param verifyUrl the link a holder can hand to a verifier
 * @param tokenId   its identity, so it can be withdrawn later
 * @param expiresAt when it lapses
 * @param serial    the credential it grants access to
 */
public record ShareTokenResponse(
        String token, String verifyUrl, UUID tokenId, Instant expiresAt, String serial) {

    static ShareTokenResponse from(IssueShareToken.Issued issued) {
        return new ShareTokenResponse(
                issued.secret(),
                "/verify/" + issued.secret(),
                issued.tokenId(),
                issued.expiresAt(),
                issued.serial());
    }
}
