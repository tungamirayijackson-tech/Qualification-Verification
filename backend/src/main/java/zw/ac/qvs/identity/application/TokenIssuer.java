package zw.ac.qvs.identity.application;

import java.time.Instant;
import zw.ac.qvs.identity.domain.UserAccount;

/**
 * Outbound port for minting access and refresh tokens.
 *
 * <p>Two tokens with deliberately different lifetimes. The access token is short-lived and
 * self-contained, so every request can be authorised without a database read. The refresh
 * token is long-lived but <b>rotating</b> and stored only as a hash, so a stolen one is usable
 * once and its reuse is detectable.
 */
public interface TokenIssuer {

    /**
     * A freshly issued pair.
     *
     * @param accessToken     short-lived bearer token
     * @param refreshToken    long-lived rotating token, returned once
     * @param accessExpiresAt when the access token stops being accepted
     */
    record Tokens(String accessToken, String refreshToken, Instant accessExpiresAt) {
    }

    /**
     * Issues a pair for a signed-in user.
     *
     * @param account the user
     * @return the tokens
     */
    Tokens issue(UserAccount account);

    /**
     * Exchanges a refresh token for a new pair, invalidating the old one.
     *
     * @param refreshToken the presented refresh token
     * @return a new pair
     */
    Tokens refresh(String refreshToken);

    /**
     * Revokes every refresh token an account holds, ending its sessions.
     *
     * <p>Called when an administrator disables an account, resets its password or resets its
     * second factor — in each case the point is that whoever is currently signed in should stop
     * being signed in.
     *
     * <p><b>This does not reach the access token.</b> That one is a self-contained JWT,
     * deliberately, so that authorising a request costs no database read (NFR-01); the price is
     * that it cannot be recalled before it expires. So the honest statement of what disabling
     * does is: no new tokens can be minted, and the existing access token stops working within
     * fifteen minutes. Making it instant would mean a database lookup on every authorised
     * request, which is a real cost paid on every request to shorten a fifteen-minute window on
     * a rare administrative action.
     *
     * @param userId whose sessions to end
     * @return how many refresh tokens were revoked
     */
    int revokeSessionsFor(java.util.UUID userId);
}
