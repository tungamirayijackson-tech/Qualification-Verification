package zw.ac.qvs.identity.adapter;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.ac.qvs.identity.adapter.out.JwtTokenIssuer;
import zw.ac.qvs.identity.application.AuthenticateUser;
import zw.ac.qvs.identity.application.PasswordHasher;
import zw.ac.qvs.identity.application.TokenIssuer;
import zw.ac.qvs.identity.application.UserRepository;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.shared.adapter.QvsProperties;

/** Wires the identity module. */
@Configuration
public class IdentityModuleConfiguration {

    @Bean
    public TokenIssuer tokenIssuer(
            JdbcTemplate jdbc, UserRepository users, Clock clock, QvsProperties properties) {
        return new JwtTokenIssuer(jdbc, users, clock, properties.crypto().jwtSecret());
    }

    @Bean
    public AuthenticateUser authenticateUser(
            UserRepository users,
            PasswordHasher passwords,
            TokenIssuer tokens,
            AppendEntry ledger,
            Clock clock) {
        return new AuthenticateUser(users, passwords, tokens, ledger, clock);
    }

    /**
     * Creating accounts. The first administrator comes from {@code BootstrapAdmin} instead,
     * because when the register is empty there is nobody to call the endpoint this serves.
     */
    @Bean
    public zw.ac.qvs.identity.application.CreateUser createUser(
            zw.ac.qvs.identity.application.UserRepository users,
            zw.ac.qvs.identity.application.PasswordHasher passwords,
            zw.ac.qvs.identity.application.KnownInstitutions institutions,
            zw.ac.qvs.ledger.application.AppendEntry ledger) {
        return new zw.ac.qvs.identity.application.CreateUser(
                users, passwords, institutions, ledger);
    }

    /**
     * Identity's one question about institutions, answered by the register that owns them: has
     * this institution been admitted? An account may only name one that has.
     */
    @Bean
    public zw.ac.qvs.identity.application.KnownInstitutions knownInstitutions(
            zw.ac.qvs.credential.application.InstitutionRepository institutions) {
        return new zw.ac.qvs.identity.adapter.out.InstitutionsFromRegister(institutions);
    }

    /**
     * Everything an administrator can do to an account after creating it: suspend it, restore
     * it, reset its password, reset its second factor.
     *
     * <p>Takes the {@link TokenIssuer} because each of those, except restoring, ends the
     * account's sessions — a withdrawal of access that leaves the holder signed in is not one.
     */
    @Bean
    public zw.ac.qvs.identity.application.ManageAccount manageAccount(
            UserRepository users,
            PasswordHasher passwords,
            TokenIssuer tokens,
            AppendEntry ledger) {
        return new zw.ac.qvs.identity.application.ManageAccount(users, passwords, tokens, ledger);
    }
}
