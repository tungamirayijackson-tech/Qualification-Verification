package zw.ac.qvs.credential.adapter.in;

import java.net.URI;
import java.util.Locale;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import zw.ac.qvs.credential.application.ImportCohort;
import zw.ac.qvs.credential.application.RegistrationRejected;

/**
 * Turns the credential module's own refusals into RFC 9457 problems.
 *
 * <p>This lives in the credential module rather than in the shared handler, and that is not
 * tidiness — it is what keeps the module graph acyclic. A shared handler that imported
 * {@link RegistrationRejected} made {@code shared} depend on {@code credential}, and since
 * {@code credential} already depends on {@code ledger} and {@code ledger} on {@code shared},
 * that closed a cycle. The ArchUnit rule failed the build and the handler moved here.
 *
 * <p>The general principle it enforces is a good one on its own: a module owns its failures.
 * Shared code handles what every module shares — validation, bad values, the unexpected — and
 * knows nothing about any particular module's vocabulary.
 *
 * <p>Advice classes are scoped by package, so this one only sees the credential controllers.
 */
// Ordered ahead of the shared advice. Both used to sit at LOWEST_PRECEDENCE -- this one by
// omission -- and that is a tie, not an ordering. It resolved in favour of the catch-all, so
// every RegistrationRejected reached the client as a 500 saying "the request could not be
// completed" instead of a 422 explaining what was wrong. Ordering only the catch-all last, as
// was done earlier, orders one end of a tie.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "zw.ac.qvs.credential.adapter.in")
public class CredentialExceptionHandler {

    private static final String TYPE_BASE = "https://qvs.ac.zw/problems/";

    /**
     * A registration the register will not accept.
     *
     * <p>422 rather than 400: the request was syntactically fine and the registrar filled it
     * in correctly. The register is refusing it on a rule, which is a different thing from a
     * malformed request and deserves a different status. FR-01 names this case explicitly.
     *
     * @param e the refusal
     * @return the problem detail
     */
    @ExceptionHandler(RegistrationRejected.class)
    public ProblemDetail onRegistrationRejected(RegistrationRejected e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        // A stable type URI per reason, so the console can map a refusal to a message without
        // matching on English prose -- a habit that breaks the first time someone rewords an
        // exception, and that makes translation impossible.
        problem.setType(URI.create(TYPE_BASE
                + e.reason().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setTitle("Registration refused");
        problem.setProperty("reason", e.reason().name());
        return problem;
    }

    /**
     * A cohort file that could not be read at all.
     *
     * <p>422 rather than 400: the upload itself was well-formed, and the registrar did nothing
     * syntactically wrong — the file's contents are the problem, and the message names exactly
     * which columns are missing so it can be fixed in one pass.
     *
     * <p>Distinct from a row failure, which is reported inside a 200 response alongside the
     * rows that did import. This status is only for a file with no usable rows at all.
     *
     * @param e the failure
     * @return the problem detail
     */
    @ExceptionHandler(ImportCohort.UnreadableFile.class)
    public ProblemDetail onUnreadableFile(ImportCohort.UnreadableFile e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "unreadable-import-file"));
        problem.setTitle("Cohort file could not be read");
        return problem;
    }
}
