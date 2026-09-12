package zw.ac.qvs.credential.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.ac.qvs.credential.application.IssueSigningKey;
import zw.ac.qvs.credential.application.ListInstitutions;
import zw.ac.qvs.credential.application.OnboardInstitution;
import zw.ac.qvs.shared.adapter.in.ActorResolver;

/**
 * Awarding bodies: who is in the register, and the two administrative acts that put them there.
 *
 * <p>Reading is open to any signed-in user — a registrar needs to see which institutions exist,
 * an auditor needs to see who was accredited when. Writing is ADMIN only, and each write is
 * appended to the audit ledger, because between them these two endpoints decide whose signature
 * this system will vouch for.
 */
@RestController
@RequestMapping("/api/v1/institutions")
@Tag(name = "Institutions", description = "Awarding bodies registered with the system")
public class InstitutionController {

    private final ListInstitutions listInstitutions;
    private final OnboardInstitution onboardInstitution;
    private final IssueSigningKey issueSigningKey;
    private final ActorResolver actors;

    public InstitutionController(
            ListInstitutions listInstitutions,
            OnboardInstitution onboardInstitution,
            IssueSigningKey issueSigningKey,
            ActorResolver actors) {
        this.listInstitutions = listInstitutions;
        this.onboardInstitution = onboardInstitution;
        this.issueSigningKey = issueSigningKey;
        this.actors = actors;
    }

    /**
     * Lists institutions.
     *
     * @param eligibleOnly when true, returns only institutions that may issue credentials today
     * @return institutions in name order
     */
    @GetMapping
    @Operation(summary = "List registered institutions")
    public List<InstitutionResponse> list(
            @RequestParam(name = "eligibleOnly", defaultValue = "false") boolean eligibleOnly) {
        var institutions = eligibleOnly ? listInstitutions.eligibleToIssue() : listInstitutions.all();
        return institutions.stream().map(InstitutionResponse::from).toList();
    }

    /**
     * Admits an awarding body to the register.
     *
     * <p>The new institution has <b>no signing key</b> and therefore cannot issue anything until
     * one is issued through the endpoint below. That is deliberate — see
     * {@link OnboardInstitution} for why the two acts are separate.
     *
     * @param request what to record
     * @return the institution, with the identity the register assigned it
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Operation(summary = "Admit an awarding body",
            description = "Records an institution. It cannot issue credentials until a signing "
                    + "key is issued for it, which is a separate call.")
    public ResponseEntity<InstitutionResponse> onboard(
            @Valid @RequestBody OnboardInstitutionRequest request) {

        var actor = actors.requireAuthenticated();
        var institution = onboardInstitution.onboard(new OnboardInstitution.Command(
                request.name(),
                request.country(),
                request.providerNumber(),
                request.accreditedUntil(),
                actor.userId()));

        return ResponseEntity.status(HttpStatus.CREATED).body(InstitutionResponse.from(institution));
    }

    /**
     * Issues the key an institution signs with, replacing the current one if it has it.
     *
     * <p>Credentials signed with the outgoing key keep verifying: verification resolves the key
     * that was valid on the award date, and every public key an institution has ever used stays
     * in the register.
     *
     * @param id      which institution
     * @param request when the new key takes effect; today when the body is absent
     * @return the new public key
     */
    @PostMapping("/{id}/keys")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    @Operation(summary = "Issue or rotate an institution's signing key",
            description = "Credentials signed with the previous key keep verifying: the key "
                    + "valid on the award date is the one that is checked.")
    public ResponseEntity<SigningKeyResponse> issueKey(
            @PathVariable UUID id,
            @RequestBody(required = false) IssueKeyRequest request) {

        var actor = actors.requireAuthenticated();
        var issued = issueSigningKey.issue(
                id, request == null ? null : request.from(), actor.userId());

        return ResponseEntity.status(HttpStatus.CREATED).body(SigningKeyResponse.from(issued));
    }
}
