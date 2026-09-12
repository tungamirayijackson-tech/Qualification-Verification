package zw.ac.qvs.shared.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is making this request.
 *
 * <p>Resolved from the authenticated principal rather than from anything the caller sends, so
 * a request cannot nominate its own actor. Every ledger entry written on behalf of a request
 * takes its actor from here, which is what makes the audit trail attributable.
 *
 * <p>Lives in the domain, not in the web adapter, because use cases need it: an application
 * service that records who did something must be able to name them without depending on the
 * HTTP layer. {@code ActorResolver} in {@code adapter/in} is what turns a request into one of
 * these, and that translation is the adapter's job.
 *
 * @param userId        the authenticated user, or null for an anonymous public check
 * @param role          the role they hold
 * @param institutionId the institution they are scoped to, null for auditors and admins
 */
public record CurrentActor(UUID userId, String role, UUID institutionId) {

    /** The actor recorded for an unauthenticated public verification. */
    public static CurrentActor anonymous() {
        return new CurrentActor(null, "ANONYMOUS", null);
    }

    /**
     * The institution this actor may act for, when they are scoped to one.
     *
     * @return the institution, or empty for a cross-institution role
     */
    public Optional<UUID> scopedInstitution() {
        return Optional.ofNullable(institutionId);
    }

    /**
     * Whether this actor may act on behalf of the given institution.
     *
     * <p>An unscoped role — auditor or admin — is not automatically permitted to act; it is
     * permitted to <em>read</em> across institutions. Write authorisation is decided by the
     * endpoint's role requirement, and this method answers only the scoping question.
     *
     * @param institution the institution in question
     * @return true when this actor's scope permits it
     */
    public boolean mayActFor(UUID institution) {
        return institutionId == null || institutionId.equals(institution);
    }
}
