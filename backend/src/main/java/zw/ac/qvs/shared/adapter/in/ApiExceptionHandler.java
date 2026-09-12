package zw.ac.qvs.shared.adapter.in;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns failures into RFC 9457 {@code application/problem+json}.
 *
 * <p>Every problem carries a stable {@code type} URI. That is what lets the Angular
 * interceptor map a failure to a user-facing message without matching on English prose — a
 * habit that breaks the first time someone rewords an exception, and that makes translation
 * impossible.
 *
 * <p>Messages are written for the person who will read them. A registrar told "422" learns
 * nothing; a registrar told that the institution's accreditation ended on a particular date
 * knows what to do next.
 *
 * <p>Only failures every module shares live here. A module's own refusals are handled inside
 * that module — see {@code CredentialExceptionHandler} — because shared code that knew one
 * module's vocabulary would make every module depend on it and back again.
 *
 * <h2>Why this extends {@code ResponseEntityExceptionHandler}</h2>
 *
 * <p>It used to not, and the {@code @ExceptionHandler(Exception.class)} below therefore caught
 * every framework-level complaint about a malformed request. Asking for a URL that does not
 * exist, using the wrong HTTP method, sending the wrong content type or posting a body that is
 * not valid JSON all came back <em>500 Internal Server Error</em>, each one logged at ERROR
 * with a full stack trace. Two things are wrong with that. It tells the client the server
 * broke when in fact the request was faulty — a distinction the whole 4xx range exists to
 * draw — and it hands any passer-by the ability to fill the operator's log with stack traces
 * by typing a bad URL, which buries the real faults the log exists to surface.
 *
 * <p>The parent class is Spring's own mapping of those exceptions to their correct statuses.
 * Extending it means this advice keeps the handlers below for what is genuinely ours, and
 * inherits correct answers for everything the framework already knows how to classify.
 *
 * <p>It is ordered last deliberately. Its {@code Exception} handler would otherwise be a
 * candidate for exceptions that a module's own advice — {@code CredentialExceptionHandler} —
 * exists to answer properly, and which advice wins between two unordered ones is not something
 * to leave to chance.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_BASE = "https://qvs.ac.zw/problems/";

    /** Bean Validation failures on an inbound DTO: the first of the three validation layers. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "One or more fields are invalid.");
        problem.setType(URI.create(TYPE_BASE + "validation-failed"));
        problem.setTitle("Invalid request");
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of(
                        "field", field.getField(),
                        "message", field.getDefaultMessage() == null
                                ? "is invalid" : field.getDefaultMessage()))
                .toList());
        return handleExceptionInternal(e, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * A domain invariant refusing an impossible value.
     *
     * <p>These reach here when a value passes bean validation but the domain still refuses it
     * — the second validation layer catching what the first did not.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "invalid-value"));
        problem.setTitle("Invalid value");
        return problem;
    }

    /** A request that is well formed but arrives when the system cannot honour it. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail onIllegalState(IllegalStateException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        problem.setType(URI.create(TYPE_BASE + "conflict"));
        problem.setTitle("Cannot be done now");
        return problem;
    }

    /**
     * A cohort file larger than the configured ceiling.
     *
     * <p>Worth its own handler rather than falling through: a registrar who has just waited
     * for a large upload deserves to be told that the file is too big and what the limit is,
     * not that the server had an internal error. The limits themselves are in
     * {@code application.yml}, where an operator can raise them.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException e,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        log.warn("upload refused as too large: {}", e.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "That file is larger than this server accepts. Split the cohort and "
                        + "upload it in parts.");
        problem.setType(URI.create(TYPE_BASE + "upload-too-large"));
        problem.setTitle("File too large");
        return handleExceptionInternal(e, problem, headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
    }

    /**
     * Anything unforeseen.
     *
     * <p>The detail is deliberately uninformative. An exception message can carry a table
     * name, a SQL fragment or a file path, and this is the one handler guaranteed to be
     * reachable from the public surface. The real cause goes to the log with the stack trace,
     * where the operator can see it and a stranger cannot.
     *
     * <p>Now that the framework's own request-level exceptions are classified by the parent
     * class, an entry in this log genuinely means something went wrong here.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        log.error("unhandled exception", e);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed.");
        problem.setType(URI.create(TYPE_BASE + "internal-error"));
        problem.setTitle("Internal error");
        return problem;
    }

    /**
     * Gives the inherited handlers a {@code type} URI too.
     *
     * <p>Spring leaves {@code type} as {@code about:blank} on the problems it builds itself,
     * which would mean the promise at the top of this class — that every problem carries a
     * stable type a client can branch on — held only for the handlers written here. The slug
     * comes from the status' own reason phrase, so it stays correct for statuses nobody has
     * thought about yet without a lookup table to forget to update.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {

        ResponseEntity<Object> response =
                super.handleExceptionInternal(e, body, headers, status, request);

        if (response != null && response.getBody() instanceof ProblemDetail problem
                && isUntyped(problem)) {
            problem.setType(URI.create(TYPE_BASE + slugFor(status)));
        }
        return response;
    }

    private static boolean isUntyped(ProblemDetail problem) {
        return problem.getType() == null || "about:blank".equals(problem.getType().toString());
    }

    private static String slugFor(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved == null) {
            return "request-rejected";
        }
        return resolved.getReasonPhrase().toLowerCase(Locale.ROOT).replace(' ', '-');
    }
}
