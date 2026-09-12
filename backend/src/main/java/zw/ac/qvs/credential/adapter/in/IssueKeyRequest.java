package zw.ac.qvs.credential.adapter.in;

import java.time.LocalDate;

/**
 * When an institution's new signing key takes effect.
 *
 * <p>The date is optional and means today when absent, which is what an administrator wants
 * almost every time. It exists because the one case that is not "now" matters: onboarding an
 * institution whose credentials predate the register, where the key has to be valid from the
 * earliest award date or none of those credentials would verify.
 *
 * @param from first day the new key is valid; today when null
 */
public record IssueKeyRequest(LocalDate from) {
}
