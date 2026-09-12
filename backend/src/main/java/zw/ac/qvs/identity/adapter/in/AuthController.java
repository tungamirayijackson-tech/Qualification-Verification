package zw.ac.qvs.identity.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.ac.qvs.identity.application.AuthenticateUser;
import zw.ac.qvs.identity.application.TokenIssuer;

/**
 * Signing in and renewing a session.
 *
 * <p>Unauthenticated by necessity, and therefore written with the same care as the public
 * verification door. Every failure produces the same status and the same message, so the
 * endpoint cannot be used to discover which addresses hold accounts.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Sign in, renew a session, enrol a second factor")
public class AuthController {

    private final AuthenticateUser authenticateUser;
    private final TokenIssuer tokens;

    public AuthController(AuthenticateUser authenticateUser, TokenIssuer tokens) {
        this.authenticateUser = authenticateUser;
        this.tokens = tokens;
    }

    /**
     * Credentials presented at sign-in.
     *
     * @param email    the login identifier
     * @param password the password
     * @param totpCode the second factor, omitted when the account has none enrolled
     */
    public record LoginRequest(
            @Email(message = "a valid email address is required")
            @NotBlank(message = "an email address is required")
            String email,

            @NotBlank(message = "a password is required")
            String password,

            String totpCode) {
    }

    /** A request to renew a session. */
    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    /** A request to finish enrolling a second factor. */
    public record EnrolRequest(@NotBlank String email, @NotBlank String totpCode) {
    }

    /**
     * The issued session.
     *
     * @param accessToken     short-lived bearer token
     * @param refreshToken    rotating renewal token
     * @param accessExpiresAt when the access token stops being accepted
     * @param tokenType       always {@code Bearer}
     */
    public record TokenResponse(
            String accessToken, String refreshToken, Instant accessExpiresAt, String tokenType) {

        static TokenResponse from(TokenIssuer.Tokens issued) {
            return new TokenResponse(issued.accessToken(), issued.refreshToken(),
                    issued.accessExpiresAt(), "Bearer");
        }
    }

    /** The secret handed out when a role that requires MFA has not yet enrolled. */
    public record EnrolmentRequired(String message, String mfaSecret, String issuer) {
    }

    /**
     * Signs in.
     *
     * @param request the credentials
     * @return access and refresh tokens
     */
    @PostMapping("/login")
    @Transactional
    @Operation(summary = "Sign in",
            description = "Returns 401 for every kind of failure, so the endpoint cannot be "
                    + "used to discover which addresses hold accounts.")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        var result = authenticateUser.signIn(new AuthenticateUser.Command(
                request.email(), request.password(), request.totpCode()));

        // 428 Precondition Required rather than 401 for the enrolment case: the caller is who
        // they say they are, and there is a specific action they must take before proceeding.
        // Answering 401 would leave a user unable to tell a wrong password from an incomplete
        // setup, and with no route out of it.
        return switch (result) {
            case AuthenticateUser.SignInResult.Authenticated authenticated ->
                    ResponseEntity.ok(TokenResponse.from(authenticated.tokens()));
            case AuthenticateUser.SignInResult.EnrolmentRequired enrolment ->
                    ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                            .body(new EnrolmentRequired(
                                    "This role requires multi-factor authentication "
                                            + "before first use.",
                                    enrolment.secret(), "QVS"));
        };
    }

    /**
     * Renews a session, rotating the refresh token.
     *
     * @param request the current refresh token
     * @return a new pair
     */
    @PostMapping("/refresh")
    @Transactional
    @Operation(summary = "Renew a session",
            description = "The presented refresh token is consumed. Presenting it twice "
                    + "revokes the whole token family.")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(TokenResponse.from(tokens.refresh(request.refreshToken())));
    }

    /**
     * Completes MFA enrolment by proving a code can be generated from the new secret.
     *
     * @param request the account and a generated code
     * @return 204 on success, 400 when the code does not match
     */
    @PostMapping("/mfa/enrol")
    @Transactional
    @Operation(summary = "Finish enrolling a second factor")
    public ResponseEntity<Void> enrol(@Valid @RequestBody EnrolRequest request) {
        boolean enrolled = authenticateUser.completeMfaEnrolment(
                request.email(), request.totpCode());
        return enrolled ? ResponseEntity.noContent().build() : ResponseEntity.badRequest().build();
    }

    /**
     * Every authentication failure, answered identically.
     *
     * <p>401 with one message. A different status or wording for "unknown email" would turn
     * this endpoint into a way of enumerating the user table, which is the most common
     * unintentional disclosure in a login form.
     */
    @ExceptionHandler(AuthenticateUser.AuthenticationFailed.class)
    public ProblemDetail onAuthenticationFailed(AuthenticateUser.AuthenticationFailed e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, e.getMessage());
        problem.setType(URI.create("https://qvs.ac.zw/problems/authentication-failed"));
        problem.setTitle("Sign-in failed");
        return problem;
    }

}
