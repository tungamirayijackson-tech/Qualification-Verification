package zw.ac.qvs.credential.adapter.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * A lookup by holder identity.
 *
 * <p>In a request body rather than a query string, deliberately. A national ID in a URL is
 * copied into proxy logs, browser history, bookmarks and referrer headers — none of which the
 * system controls, and all of which outlive the request.
 *
 * @param holderNationalId the plaintext identifier; hashed on arrival and never stored
 * @param status           ISSUED or REVOKED, or null for both
 * @param page             zero-based page number, null for the first
 * @param size             rows per page, null for the default
 */
public record HolderSearchRequest(
        @NotBlank(message = "the holder's national ID is required")
        // Written without backslash classes so the shape stays readable in an annotation:
        // registration office, serial, check letter, district, with hyphens or spaces or
        // neither. NationalId re-reads it and is the single authority on the canonical form.
        @Pattern(regexp = "^ *[0-9]{2} *[- ]? *[0-9]{6,7} *[- ]? *[A-Za-z] *[- ]? *[0-9]{2} *$",
                message = "a national ID looks like 63-1234567 K 42: two digits, six or seven "
                        + "digits, a letter, then two digits")
        String holderNationalId,

        String status,
        Integer page,
        Integer size) {

    int pageOrZero() {
        return page == null || page < 0 ? 0 : page;
    }

    int sizeOrDefault() {
        return size == null ? 20 : size;
    }
}
