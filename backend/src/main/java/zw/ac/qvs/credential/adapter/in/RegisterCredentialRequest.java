package zw.ac.qvs.credential.adapter.in;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A registrar's submission.
 *
 * <p>These annotations are the first of the three validation layers. They catch what is
 * malformed; the domain constructors catch what is impossible; the database constraints catch
 * whatever got past both. Each layer has its own negative tests, so a rule cannot be quietly
 * dropped from one of them and go unnoticed because the other two still hold.
 *
 * <p>The institution is deliberately absent from this shape: it comes from the registrar's own
 * token, so a request cannot claim to act for somewhere else.
 *
 * @param qualificationId   what was conferred
 * @param holderNationalId  the holder's national ID; hashed on arrival and never stored
 * @param holderName        the holder's name as conferred
 * @param holderDateOfBirth optional, disambiguates identical names
 * @param awardedOn         the date of conferral
 */
public record RegisterCredentialRequest(
        @NotNull(message = "a qualification is required")
        UUID qualificationId,

        @NotBlank(message = "the holder's national ID is required")
        // Written without backslash classes so the shape stays readable in an annotation:
        // registration office, serial, check letter, district, with hyphens or spaces or
        // neither. NationalId re-reads it and is the single authority on the canonical form.
        @Pattern(regexp = "^ *[0-9]{2} *[- ]? *[0-9]{6,7} *[- ]? *[A-Za-z] *[- ]? *[0-9]{2} *$",
                message = "a national ID looks like 63-1234567 K 42: two digits, six or seven "
                        + "digits, a letter, then two digits")
        String holderNationalId,

        @NotBlank(message = "the holder's name is required")
        @Size(max = 200, message = "a name may be at most 200 characters")
        String holderName,

        @Past(message = "a date of birth must be in the past")
        LocalDate holderDateOfBirth,

        @NotNull(message = "the award date is required")
        @PastOrPresent(message = "an award cannot be conferred in the future")
        LocalDate awardedOn) {
}
