package zw.ac.qvs.identity.adapter.in;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import zw.ac.qvs.identity.domain.Role;

/**
 * What an administrator sends to create an account.
 *
 * <p>There is deliberately no password field. The initial password is generated and returned
 * once -- an administrator choosing one picks a memorable one, and a memorable password handed
 * over in an email is the weakest link in an otherwise careful chain.
 *
 * @param email         the address they will sign in with
 * @param displayName   how they are named on screen and in the ledger
 * @param role          what they may do
 * @param institutionId the institution a registrar acts for; must be absent for every other role
 */
public record CreateUserRequest(
        @NotBlank(message = "an email address is required")
        @Email(message = "that is not an email address")
        @Size(max = 254, message = "that address is longer than the register allows")
        String email,

        @NotBlank(message = "a display name is required")
        @Size(max = 120, message = "that name is longer than the register allows")
        String displayName,

        @NotNull(message = "a role is required")
        Role role,

        UUID institutionId) {
}
