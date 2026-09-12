package zw.ac.qvs.identity.application;

import java.util.UUID;

/**
 * Whether an institution is in the register.
 *
 * <p>A port rather than a direct call into the credential module, and a deliberately narrow one:
 * creating an account needs to know that an institution <em>exists</em>, and nothing else about
 * it. Identity has no business reading an institution's accreditation date or its signing key.
 *
 * <p>It exists because the order of operations matters. An administrator admits an institution
 * first, and only then can create a registrar who acts for it. Before this, a registrar could be
 * created against any UUID at all: the foreign key caught it, but a constraint violation reaches
 * an administrator as a failure with no sentence in it, and the account they thought they had
 * made did not exist.
 */
public interface KnownInstitutions {

    /**
     * Whether the register holds this institution.
     *
     * @param institutionId the institution named on the account
     * @return true when it has been admitted
     */
    boolean exists(UUID institutionId);
}
