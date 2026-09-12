package zw.ac.qvs.credential.domain;

/**
 * Whether a credential currently stands.
 *
 * <p>Only two states. There is deliberately no {@code SUSPENDED} or {@code PENDING}: a
 * verifier's question is binary, and an intermediate state would have to resolve to one of
 * these two on the public path anyway, with the mapping decided somewhere less visible.
 */
public enum CredentialStatus {
    ISSUED,
    REVOKED
}
