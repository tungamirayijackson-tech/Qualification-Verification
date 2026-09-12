package zw.ac.qvs.verification.application;

import zw.ac.qvs.verification.domain.SignedVerificationReport;

/**
 * Turns a signed report into the document a verifier keeps.
 *
 * <p>A port rather than a call to a PDF library, so the application layer states what it wants
 * without depending on how it is drawn. That matters more here than it looks: the rendered file
 * is the thing a verifier will still have in two years, and the format it is in is a decision
 * about longevity, not a detail of this use case.
 */
public interface ReportRenderer {

    /**
     * Renders the report.
     *
     * @param report the signed statement
     * @return the document bytes
     */
    byte[] render(SignedVerificationReport report);

    /**
     * The media type of what {@link #render} produces.
     *
     * @return an IANA media type
     */
    String mediaType();

    /**
     * The file extension, without a dot.
     *
     * @return an extension for the download filename
     */
    String fileExtension();
}
