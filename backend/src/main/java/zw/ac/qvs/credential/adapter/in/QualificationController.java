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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import zw.ac.qvs.credential.application.AddQualification;
import zw.ac.qvs.credential.application.QualificationRepository;
import zw.ac.qvs.credential.application.RegistrationRejected;
import zw.ac.qvs.shared.adapter.in.ActorResolver;
import zw.ac.qvs.shared.domain.CurrentActor;

/**
 * The qualifications an institution offers.
 *
 * <p>This endpoint exists as much for usability as for administration. Registering an award
 * requires a qualification's identity, and until now the console asked a registrar to type a
 * UUID they had no way of looking up. A register that expects somebody to know a UUID by heart
 * is a register that will be given the wrong one.
 *
 * <h2>Who may add one, and to whom</h2>
 *
 * <p>A <b>registrar</b> may add qualifications to their own institution and no other. The
 * institution comes from their token and any {@code institutionId} in the body is ignored — the
 * same rule search follows, and for the same reason: a value a client can send is a value a
 * client can change.
 *
 * <p>An <b>administrator</b> is not institution-scoped, so they must name the institution. This
 * is the one place where an absent field is an error for one role and ignored for another,
 * which is why the check lives here rather than in a validation annotation.
 */
@RestController
@RequestMapping("/api/v1/qualifications")
@Tag(name = "Qualifications", description = "What each awarding body offers")
public class QualificationController {

    private final AddQualification addQualification;
    private final QualificationRepository qualifications;
    private final ActorResolver actors;

    public QualificationController(
            AddQualification addQualification,
            QualificationRepository qualifications,
            ActorResolver actors) {
        this.addQualification = addQualification;
        this.qualifications = qualifications;
        this.actors = actors;
    }

    /**
     * Lists the qualifications of one institution.
     *
     * @param institutionId which institution; defaults to the caller's own for a registrar
     * @return qualifications, in the order the repository returns them
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRAR', 'AUDITOR', 'ADMIN')")
    @Operation(summary = "List an institution's qualifications",
            description = "A registrar sees their own institution's; the parameter is ignored "
                    + "for them.")
    public List<QualificationResponse> list(
            @RequestParam(name = "institutionId", required = false) UUID institutionId) {

        CurrentActor actor = actors.requireAuthenticated();

        // Scope wins over the parameter, never the other way round.
        UUID target = actor.scopedInstitution().orElse(institutionId);
        if (target == null) {
            throw new IllegalArgumentException(
                    "institutionId is required for a role that is not scoped to one institution");
        }

        return qualifications.findByInstitution(target).stream()
                .map(QualificationResponse::from)
                .toList();
    }

    /**
     * Records a qualification, for the registrar's own institution.
     *
     * <p><b>Registrars only.</b> An administrator admits an institution to the register and
     * gives it a signing key; what that institution awards is the institution's own business,
     * and a registrar is the person who speaks for it. An administrator recording a
     * qualification against somebody else's institution is a central authority deciding what a
     * university offers, which is the opposite of what this register is for.
     *
     * <p>The institution therefore comes from the caller's token and from nowhere else. The
     * request has no field for one, so there is nothing to validate and nothing to ignore.
     *
     * @param request what to record
     * @return the qualification, with the identity a registrar will quote when awarding it
     */
    @PostMapping
    @PreAuthorize("hasRole('REGISTRAR')")
    @Transactional
    @Operation(summary = "Record a qualification the registrar's own institution offers",
            description = "Registrars only. The institution comes from the caller's token.")
    public ResponseEntity<QualificationResponse> add(
            @Valid @RequestBody AddQualificationRequest request) {

        CurrentActor actor = actors.requireAuthenticated();

        UUID institutionId = actor.scopedInstitution().orElseThrow(() ->
                new RegistrationRejected(RegistrationRejected.Reason.UNKNOWN_REFERENCE,
                        "Only a registrar can record a qualification, for their own "
                                + "institution."));

        var stored = addQualification.add(new AddQualification.Command(
                institutionId,
                request.title(),
                request.nqfLevel(),
                request.credits(),
                request.saqaQualId(),
                actor.userId(),
                actor.role()));

        return ResponseEntity.status(HttpStatus.CREATED).body(QualificationResponse.from(stored));
    }
}
