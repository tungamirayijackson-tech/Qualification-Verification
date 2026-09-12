package zw.ac.qvs.identity.adapter.in;

import zw.ac.qvs.identity.application.CreateUser;

/**
 * A newly created account, and the one and only sight of its password.
 *
 * <p>The password is returned here and nowhere else, ever again: it is stored only as a bcrypt
 * hash, so this system genuinely cannot show it a second time. That is the same shape as a share
 * token, and it is why the console tells the administrator to pass it on now rather than come
 * back for it.
 *
 * @param account         the account
 * @param initialPassword the generated password, shown once
 */
public record CreatedUserResponse(UserResponse account, String initialPassword) {

    static CreatedUserResponse from(CreateUser.Created created) {
        return new CreatedUserResponse(
                UserResponse.from(created.account()), created.initialPassword());
    }

    /**
     * A reset password, which is the same thing said about an account that already existed.
     *
     * @param reset the account and its new password
     * @return the response
     */
    static CreatedUserResponse from(
            zw.ac.qvs.identity.application.ManageAccount.PasswordReset reset) {
        return new CreatedUserResponse(
                UserResponse.from(reset.account()), reset.newPassword());
    }
}
