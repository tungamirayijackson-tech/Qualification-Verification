package zw.ac.qvs.credential.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RestController;
import zw.ac.qvs.credential.application.CredentialSearch;
import zw.ac.qvs.credential.application.ImportCohort;
import zw.ac.qvs.credential.application.RegisterCredential;
import zw.ac.qvs.credential.application.RevokeCredential;
import zw.ac.qvs.credential.application.SearchCredentials;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.Serial;
import zw.ac.qvs.shared.adapter.in.ActorResolver;
import zw.ac.qvs.verification.application.IssueShareToken;
import zw.ac.qvs.verification.application.VerificationReadModel;

/**
 * The registrar's console endpoints.
 *
 * <p>Authenticated and institution-scoped, in contrast to the public verification door. The
 * role requirements are declared twice — here with {@code @PreAuthorize} and again in the
 * filter chain — and that duplication is deliberate. The filter chain is the policy an
 * assessor can read in one place; the annotation is the guard that survives a URL being
 * remapped and the matcher silently ceasing to apply.
 *
 * <p>Each write method is {@code @Transactional} because the ledger append must commit with
 * the state change it describes. That is not incidental: the ledger repository declares
 * {@code Propagation.MANDATORY}, so a write that forgot the annotation would fail loudly at
 * the first attempt rather than quietly recording nothing.
 */
@RestController
@RequestMapping("/api/v1/credentials")
@Tag(name = "Credentials", description = "Register, retrieve and revoke qualifications")
public class CredentialController {

    private final RegisterCredential registerCredential;
    private final RevokeCredential revokeCredential;
    private final SearchCredentials searchCredentials;
    private final ImportCohort importCohort;
    private final IssueShareToken issueShareToken;
    private final VerificationReadModel readModel;
    private final ActorResolver actors;

    public CredentialController(
            RegisterCredential registerCredential,
            RevokeCredential revokeCredential,
            SearchCredentials searchCredentials,
            ImportCohort importCohort,
            IssueShareToken issueShareToken,
            VerificationReadModel readModel,
            ActorResolver actors) {
        this.registerCredential = registerCredential;
        this.revokeCredential = revokeCredential;
        this.searchCredentials = searchCredentials;
        this.importCohort = importCohort;
        this.issueShareToken = issueShareToken;
        this.readModel = readModel;
        this.actors = actors;
    }

    /**
     * FR-01: register and sign a qualification.
     *
     * @param request the registration
     * @return 201 with the credential's serial
     */
    @PostMapping
    @PreAuthorize("hasRole('REGISTRAR')")
    @Transactional
    @Operation(summary = "Register and sign a qualification",
            description = "422 when the institution was not accredited on the award date.")
    public ResponseEntity<CredentialSummary> register(
            @Valid @RequestBody RegisterCredentialRequest request) {

        var actor = actors.requireAuthenticated();

        // The institution comes from the actor's own token, never from the request body. A
        // registrar cannot register an award on behalf of an institution they are not bound
        // to, however the request is constructed.
        var institutionId = actor.scopedInstitution().orElseThrow(() ->
                new IllegalStateException("this account is not bound to an institution"));

        Credential credential = registerCredential.register(new RegisterCredential.Command(
                institutionId,
                request.qualificationId(),
                request.holderNationalId(),
                request.holderName(),
                request.holderDateOfBirth(),
                request.awardedOn(),
                actor.userId()));

        return ResponseEntity
                .created(URI.create("/api/v1/credentials/" + credential.serial().value()))
                .body(CredentialSummary.from(credential));
    }

    /**
     * FR-02: import a graduation cohort from CSV.
     *
     * <p>Deliberately <b>not</b> {@code @Transactional}. Every other write on this controller
     * is, because a credential and its ledger entry must commit together — but an import needs
     * each row to commit on its own, so that one malformed row rolls back itself and nothing
     * else. Wrapping the loop in a transaction would make a single bad row discard the whole
     * cohort, which is precisely what FR-02 forbids. The per-row transaction lives in
     * {@code TransactionalRegistration}.
     *
     * <p>Always answers 200, even when rows failed. The upload succeeded and produced a report;
     * a 4xx would suggest the registrar should retry the whole file, when what they need to do
     * is fix the lines the report names.
     *
     * @param file a CSV with a header row naming nationalId, holderName, qualificationId and
     *             awardedOn, plus an optional dateOfBirth
     * @return what happened to every row
     */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('REGISTRAR')")
    @Operation(summary = "Import a graduation cohort from CSV",
            description = "Each row is registered independently; a malformed row fails alone and "
                    + "is reported by line number.")
    public ImportReportResponse importCohort(@RequestPart("file") MultipartFile file) {
        var actor = actors.requireAuthenticated();
        var institutionId = actor.scopedInstitution().orElseThrow(() ->
                new IllegalStateException("this account is not bound to an institution"));

        if (file.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file is empty.");
        }

        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            return ImportReportResponse.from(
                    importCohort.importFrom(reader, institutionId, actor.userId()));
        } catch (IOException e) {
            throw new IllegalArgumentException("The uploaded file could not be read.");
        }
    }

    /**
     * FR-03: search the register.
     *
     * <p>Every parameter is optional, and the institution is deliberately not among them — a
     * registrar's search is pinned to their own institution by the use case, from their signed
     * token. An auditor searches across all of them.
     *
     * <p>{@code holderNationalId} is accepted as plaintext and hashed before it reaches a query,
     * so the identifier never appears in a WHERE clause. It arrives in a POST body rather than a
     * query string for the same reason a password does: query strings end up in proxy logs,
     * browser history and referrer headers.
     *
     * @param holderName  part of a holder's name
     * @param nqfLevel    exact NQF level
     * @param status      ISSUED or REVOKED
     * @param awardedFrom inclusive lower bound on the award date
     * @param awardedTo   inclusive upper bound on the award date
     * @param page        zero-based page number
     * @param size        rows per page, clamped to 100
     * @return a page of matches
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('REGISTRAR','AUDITOR')")
    @Operation(summary = "Search qualification records",
            description = "Results are scoped to the caller's institution unless the caller is "
                    + "an auditor. Holders appear as initials only.")
    public CredentialSearchResponse search(
            @RequestParam(required = false) String holderName,
            @RequestParam(required = false) Integer nqfLevel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate awardedFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate awardedTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var query = new CredentialSearch.Query(holderName, null, null, nqfLevel, status,
                awardedFrom, awardedTo, page, size);

        return CredentialSearchResponse.from(
                searchCredentials.search(query, actors.requireAuthenticated()));
    }

    /**
     * FR-03, by holder identity.
     *
     * <p>A POST because it carries a national ID. The identifier is hashed on arrival and the
     * hash is what reaches the database; the plaintext exists for the length of one method call
     * and is never logged, indexed or returned.
     *
     * @param request the identity to look up and the paging window
     * @return every credential held by that person, within the caller's scope
     */
    @PostMapping("/search-by-holder")
    @PreAuthorize("hasAnyRole('REGISTRAR','AUDITOR')")
    @Operation(summary = "Find every credential held by one person",
            description = "The national ID is hashed on arrival and never stored or logged.")
    public CredentialSearchResponse searchByHolder(
            @Valid @RequestBody HolderSearchRequest request) {

        String hash = searchCredentials.hashNationalId(request.holderNationalId());
        var query = new CredentialSearch.Query(null, hash, null, null, request.status(),
                null, null, request.pageOrZero(), request.sizeOrDefault());

        return CredentialSearchResponse.from(
                searchCredentials.search(query, actors.requireAuthenticated()));
    }

    /**
     * FR-04: retrieve one credential in full.
     *
     * @param serial the credential serial
     * @return the record, with signature and revocation state
     */
    @GetMapping("/{serial}")
    @PreAuthorize("hasAnyRole('REGISTRAR','AUDITOR')")
    @Operation(summary = "Retrieve one credential with its issuance chain",
            description = "A registrar sees their own institution's records only; anything else "
                    + "is 404, exactly as an unknown serial is. An auditor reads across all.")
    public ResponseEntity<CredentialDetail> detail(@PathVariable String serial) {
        var actor = actors.requireAuthenticated();

        return readModel.findBySerialForConsole(serial)
                // Search already scoped its results; this did not, and a serial is guessable by
                // construction -- ZW-PR0142-2026-000001 names the institution and counts from
                // one. So a registrar who knew the shape could read another university's award
                // in full. Absent rather than forbidden, so the reply says nothing about
                // whether that serial exists.
                .filter(credential -> actor.scopedInstitution()
                        .map(institution -> institution.equals(credential.institutionId()))
                        .orElse(true))
                .map(CredentialDetail::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * FR-07: withdraw a credential.
     *
     * @param serial  the credential
     * @param request the reason
     * @return the revoked record
     */
    @PostMapping("/{serial}/revoke")
    @PreAuthorize("hasRole('REGISTRAR')")
    @Transactional
    @Operation(summary = "Revoke a credential",
            description = "The signature remains valid: the award was made, and later withdrawn.")
    public ResponseEntity<CredentialSummary> revoke(
            @PathVariable String serial, @Valid @RequestBody RevokeCredentialRequest request) {

        var actor = actors.requireAuthenticated();
        Credential revoked = revokeCredential.revoke(new RevokeCredential.Command(
                new Serial(serial), request.reason(), request.note(), actor.userId(),
                actor.scopedInstitution().orElse(null)));

        return ResponseEntity.ok(CredentialSummary.from(revoked));
    }

    /**
     * FR-06: mint a share token so a holder can let one verifier check this credential.
     *
     * @param serial  the credential
     * @param request the requested lifetime and label
     * @return the token, shown exactly once
     */
    @PostMapping("/{serial}/share")
    @PreAuthorize("hasRole('REGISTRAR')")
    @Transactional
    @Operation(summary = "Mint an expiring share token",
            description = "The token is returned once and stored only as a hash.")
    public ResponseEntity<ShareTokenResponse> share(
            @PathVariable String serial, @Valid @RequestBody ShareTokenRequest request) {

        var actor = actors.requireAuthenticated();
        var issued = issueShareToken.issue(
                serial, request.ttlDays(), request.label(), actor.userId(),
                actor.scopedInstitution().orElse(null));

        return ResponseEntity.ok(ShareTokenResponse.from(issued));
    }
}
