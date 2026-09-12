package zw.ac.qvs.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Claims that the annotated test verifies one or more requirements from the register.
 *
 * <p>This is the mechanism behind Decision 11-A. {@code traceabilityReport} scans the test
 * sources for these annotations, cross-references {@code docs/requirements.md}, and fails the
 * build when a {@code required} row has no covering test. The matrix in the report is
 * therefore generated evidence rather than a table somebody typed and then stopped updating.
 *
 * <pre>{@code
 * @Test
 * @Requirement("FR-06")
 * void unknownTokenAndUnknownCredentialAreIndistinguishable() { ... }
 * }</pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Requirement {

    /**
     * Requirement ids this test covers, e.g. {@code "FR-06"} or {@code "NFR-02"}.
     *
     * @return one or more ids exactly as they appear in the register
     */
    String[] value();
}
