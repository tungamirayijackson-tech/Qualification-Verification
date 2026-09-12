package zw.ac.qvs.shared.adapter.in;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import zw.ac.qvs.shared.domain.CurrentActor;

/**
 * Resolves who is making the current request, from the signed token and nothing else.
 *
 * <p>Every value here comes out of claims the server signed. Nothing is read from a header, a
 * query parameter or a request body, which is what makes an audit entry attributable: a caller
 * cannot nominate somebody else as the actor for an action they took.
 */
@Component
public class ActorResolver {

    /**
     * The actor for the current request.
     *
     * @return the authenticated actor, or {@link CurrentActor#anonymous()} on the public path
     */
    public CurrentActor current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return CurrentActor.anonymous();
        }
        return new CurrentActor(
                parseUuid(jwt.getSubject()),
                jwt.getClaimAsString("role"),
                parseUuid(jwt.getClaimAsString("inst")));
    }

    /**
     * The actor for the current request, insisting there is one.
     *
     * @return the authenticated actor
     * @throws IllegalStateException when the request is not authenticated
     */
    public CurrentActor requireAuthenticated() {
        CurrentActor actor = current();
        if (actor.userId() == null) {
            throw new IllegalStateException("this action requires an authenticated user");
        }
        return actor;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            // A malformed claim in a token this server signed means something is badly wrong,
            // but the safe reading is "no actor" rather than a crash on every request.
            return null;
        }
    }
}
