package zw.ac.qvs.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import zw.ac.qvs.testsupport.Requirement;

/**
 * Decision 04-A made executable.
 *
 * <p>A modular monolith is only defensible if the modules are real. These rules are what make
 * the claim in the report checkable: cross the boundary and the build goes red, in the same
 * stage as a failing unit test.
 */
@AnalyzeClasses(
        packages = "zw.ac.qvs",
        importOptions = ImportOption.DoNotIncludeTests.class)
@Requirement({"ARCH-01", "ARCH-02", "ARCH-03"})
class ModuleBoundaryTest {

    /**
     * The domain is plain Java. No Spring, no JPA, no web -- which is precisely why the
     * interesting logic can be unit-tested with no context load.
     */
    @ArchTest
    static final ArchRule domainIsFrameworkFree =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "io.swagger..",
                            "com.fasterxml.jackson..")
                    .because("domain logic must be testable and portable without a container");

    /** Use cases talk to ports, never to the adapters that implement them. */
    @ArchTest
    static final ArchRule applicationDoesNotSeeAdapters =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAPackage("..adapter..")
                    .because("the dependency must point inward, or hexagonal buys us nothing");

    /** The domain is the innermost ring; it does not know its callers. */
    @ArchTest
    static final ArchRule domainDoesNotSeeOuterRings =
            ArchRuleDefinition.noClasses()
                    .that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..");

    /** HTTP lives at one edge only, so the unauthenticated surface stays easy to audit. */
    @ArchTest
    static final ArchRule controllersOnlyAtTheInboundEdge =
            ArchRuleDefinition.classes()
                    .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .should().resideInAPackage("..adapter.in..")
                    .because("S09 depends on being able to enumerate every entry point by package");

    /** JPA stays behind the outbound edge; the domain never becomes an entity graph. */
    @ArchTest
    static final ArchRule entitiesOnlyAtTheOutboundEdge =
            ArchRuleDefinition.classes()
                    .that().areAnnotatedWith("jakarta.persistence.Entity")
                    .should().resideInAPackage("..adapter.out..");

    /** Guards against an empty import silently making every rule above vacuous. */
    @ArchTest
    static void rulesActuallyHaveSomethingToCheck(JavaClasses classes) {
        if (classes.size() < 5) {
            throw new AssertionError("expected the production classes to be imported, got " + classes.size());
        }
    }
}
