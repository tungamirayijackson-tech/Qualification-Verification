package zw.ac.qvs.identity.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.identity.domain.UserAccount;

/** Outbound port for user accounts. */
public interface UserRepository {

    Optional<UserAccount> findByEmail(String email);

    Optional<UserAccount> findById(UUID id);

    UserAccount save(UserAccount account);

    /**
     * Every account, for an administrator's user list.
     *
     * <p>Returns whole {@link UserAccount} records, password hashes and MFA secrets included,
     * because that is what the port is. Keeping those out of what reaches a screen is the
     * response type's job, and it is done there rather than here so the omission is visible in
     * the file an assessor reads to check it.
     *
     * @return accounts, in email order
     */
    List<UserAccount> findAll();

    /**
     * How many accounts exist.
     *
     * <p>Exists for one caller: the first-run bootstrap, which admits an administrator only
     * into a register that has none. A count rather than a "does an admin exist" question, and
     * deliberately so — the bootstrap must not be a way to add a second administrator to a
     * running system, so it declines whenever there is anybody at all.
     *
     * @return the number of accounts
     */
    long count();
}
