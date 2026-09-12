package zw.ac.qvs.verification.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.verification.domain.ShareToken;

/** Outbound port for share tokens. */
public interface ShareTokenRepository {

    ShareToken save(ShareToken token);

    Optional<ShareToken> findById(UUID id);

    /**
     * Every token minted for a credential, newest first, so the holder can revoke one.
     *
     * @param credentialId the credential
     * @return the tokens
     */
    List<ShareToken> findByCredential(UUID credentialId);
}
