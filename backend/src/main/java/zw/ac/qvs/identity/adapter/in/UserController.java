package zw.ac.qvs.identity.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.ac.qvs.identity.application.CreateUser;
import zw.ac.qvs.identity.application.ManageAccount;
import zw.ac.qvs.identity.application.UserRepository;
import zw.ac.qvs.shared.adapter.in.ActorResolver;

/**
 * Accounts. Administrators only, and every creation is recorded in the ledger.
 *
 * <p>This is the endpoint through which every power in the system is handed out: a registrar
 * created here can sign credentials on an institution's behalf, and another administrator
 * created here can do everything this one can. It is ADMIN-only for that reason, and the entry
 * it writes names both the account and the administrator who created it.
 *
 * <p>The <b>first</b> administrator does not come from here — nobody exists to call it. That one
 * is created at start-up from configuration, only into a register with no accounts at all; see
 * {@code BootstrapAdmin}.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Accounts and the roles they hold")
public class UserController {

    private final CreateUser createUser;
    private final ManageAccount manageAccount;
    private final UserRepository users;
    private final ActorResolver actors;

    public UserController(CreateUser createUser, ManageAccount manageAccount,
            UserRepository users, ActorResolver actors) {
        this.createUser = createUser;
        this.manageAccount = manageAccount;
        this.users = users;
        this.actors = actors;
    }

    /**
     * Lists accounts.
     *
     * @return every account, without password or second-factor material
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List accounts",
            description = "Password hashes and MFA secrets are never returned.")
    public List<UserResponse> list() {
        return users.findAll().stream().map(UserResponse::from).toList();
    }

    /**
     * Creates an account and returns its initial password, once.
     *
     * @param request what to create
     * @return the account and the password, which this system will never show again
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Operation(summary = "Create an account",
            description = "The initial password is generated and returned once. It is stored "
                    + "only as a hash, so it cannot be shown again. A role requiring a second "
                    + "factor collects it on the new user's first sign-in.")
    public ResponseEntity<CreatedUserResponse> create(
            @Valid @RequestBody CreateUserRequest request) {

        var actor = actors.requireAuthenticated();
        var created = createUser.create(
                new CreateUser.Command(
                        request.email(),
                        request.displayName(),
                        request.role(),
                        request.institutionId()),
                actor.userId(),
                actor.role());

        return ResponseEntity.status(HttpStatus.CREATED).body(CreatedUserResponse.from(created));
    }

    /**
     * Suspends an account, so it can no longer sign in.
     *
     * <p>A POST to a sub-resource rather than a PATCH of a flag, deliberately. Nothing in this
     * system edits a row in place where the change is itself an event worth recording, and
     * withdrawing somebody's access is such an event: the request names an action, and the
     * action writes a ledger entry.
     *
     * @param id whose access to withdraw
     * @return the suspended account
     */
    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Operation(summary = "Suspend an account",
            description = "Refuses sign-in and revokes every refresh token the account holds. "
                    + "An access token already issued keeps working until it expires, which is "
                    + "at most fifteen minutes. The account is never deleted: the ledger names "
                    + "it in every entry it took part in.")
    public UserResponse disable(@PathVariable UUID id) {
        var actor = actors.requireAuthenticated();
        return UserResponse.from(manageAccount.disable(id, actor.userId(), actor.role()));
    }

    /**
     * Gives a suspended account its access back.
     *
     * @param id whose access to restore
     * @return the restored account
     */
    @PostMapping("/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Operation(summary = "Restore a suspended account",
            description = "Does not reset the password. Somebody returning signs in with what "
                    + "they had; a password that needs replacing is replaced explicitly.")
    public UserResponse restore(@PathVariable UUID id) {
        var actor = actors.requireAuthenticated();
        return UserResponse.from(manageAccount.restore(id, actor.userId(), actor.role()));
    }

    /**
     * Issues a new password for an account and returns it, once.
     *
     * @param id whose password to replace
     * @return the account and its new password
     */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Operation(summary = "Reset a password",
            description = "The new password is generated, returned once and stored only as a "
                    + "hash. Every session the account holds is ended.")
    public CreatedUserResponse resetPassword(@PathVariable UUID id) {
        var actor = actors.requireAuthenticated();
        return CreatedUserResponse.from(
                manageAccount.resetPassword(id, actor.userId(), actor.role()));
    }

    /**
     * Clears an account's second factor, so the next sign-in enrols a new one.
     *
     * @param id whose second factor to clear
     * @return the account, now unenrolled
     */
    @PostMapping("/{id}/reset-mfa")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Operation(summary = "Reset a second factor",
            description = "The answer to a lost phone. The stored secret is dropped, not kept, "
                    + "and a fresh one is enrolled on the next sign-in.")
    public UserResponse resetSecondFactor(@PathVariable UUID id) {
        var actor = actors.requireAuthenticated();
        return UserResponse.from(
                manageAccount.resetSecondFactor(id, actor.userId(), actor.role()));
    }

    /**
     * Turns a refusal into something the administrator can act on.
     *
     * <p>Handled on the controller rather than in an advice: a controller-local handler wins
     * over every advice, so this cannot be lost to the ordering problem that once turned every
     * domain refusal in the credential module into a 500.
     */
    @ExceptionHandler(CreateUser.Rejected.class)
    public ProblemDetail onRejected(CreateUser.Rejected rejected) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, rejected.getMessage());
        problem.setType(java.net.URI.create("https://qvs.ac.zw/problems/user-rejected"));
        problem.setTitle("Account not created");
        return problem;
    }

    /**
     * The same, for the things done to an account after it exists.
     *
     * <p>422 rather than 409: the request was understood and is refusable for a reason about
     * the account's state, and the sentence explains which. "This is the only administrator who
     * can still sign in" is a refusal an administrator can act on; a conflict code alone is not.
     */
    @ExceptionHandler(ManageAccount.Rejected.class)
    public ProblemDetail onRefused(ManageAccount.Rejected rejected) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, rejected.getMessage());
        problem.setType(java.net.URI.create("https://qvs.ac.zw/problems/account-action-refused"));
        problem.setTitle("Account unchanged");
        return problem;
    }

    /**
     * An account that is not in the register.
     *
     * @param missing what was asked for
     * @return a 404 naming it
     */
    @ExceptionHandler(ManageAccount.NotFound.class)
    public ProblemDetail onMissing(ManageAccount.NotFound missing) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, missing.getMessage());
        problem.setType(java.net.URI.create("https://qvs.ac.zw/problems/account-not-found"));
        problem.setTitle("No such account");
        return problem;
    }
}
