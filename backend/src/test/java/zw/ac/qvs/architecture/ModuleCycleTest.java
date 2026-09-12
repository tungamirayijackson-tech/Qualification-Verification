package zw.ac.qvs.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The five modules must be free of cycles.
 *
 * <p>This rule earned its place. The dev-data seeder originally lived in {@code shared} and
 * needed the credential, identity and verification modules to do its job — which made shared
 * depend on credential, and since credential already depended on ledger and ledger on shared,
 * that closed a three-module cycle nobody had noticed. The build went red and the seeder moved
 * to {@code bootstrap}.
 *
 * <p>It sits in its own class because {@code bootstrap} has to be excluded from the import,
 * and {@code @AnalyzeClasses} applies to a whole class. Excluding it is correct rather than
 * convenient: {@code bootstrap} is a composition root, not a module. It depends on all of them
 * and nothing depends on it, so it can never take part in a cycle — but a slice analysis that
 * included it would report one.
 */
@AnalyzeClasses(
        packages = "zw.ac.qvs",
        importOptions = {
            ImportOption.DoNotIncludeTests.class,
            ModuleCycleTest.ExcludeCompositionRoot.class
        })
@Requirement("ARCH-04")
class ModuleCycleTest {

    /** Leaves the composition root out of the module graph. */
    static final class ExcludeCompositionRoot implements ImportOption {

        @Override
        public boolean includes(Location location) {
            return !location.contains("/zw/ac/qvs/bootstrap/");
        }
    }

    @ArchTest
    static final ArchRule modulesAreAcyclic =
            SlicesRuleDefinition.slices()
                    .matching("zw.ac.qvs.(*)..")
                    .should().beFreeOfCycles();
}
