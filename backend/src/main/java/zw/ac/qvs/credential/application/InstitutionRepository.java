package zw.ac.qvs.credential.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.credential.domain.Institution;

/**
 * Outbound port for reading institutions.
 *
 * <p>Declared here, implemented in {@code adapter/out}. The dependency therefore points
 * inward, which is the rule ArchUnit enforces.
 */
public interface InstitutionRepository {

    /**
     * Lists every institution in registration-name order.
     *
     * @return institutions, never null
     */
    List<Institution> findAll();

    /**
     * Finds one institution.
     *
     * @param id institution identity
     * @return the institution, or empty when unknown
     */
    Optional<Institution> findById(UUID id);

    /**
     * Finds an institution by its national provider registration number.
     *
     * <p>Exists so onboarding can refuse a duplicate with a sentence naming the institution
     * that already holds the number, rather than letting a unique-constraint violation surface
     * as a 500 with a database error in it.
     *
     * @param providerNumber the national provider registration number
     * @return the institution, or empty when the number is unused
     */
    Optional<Institution> findByProviderNumber(String providerNumber);

    /**
     * Stores an institution, inserting or updating by identity.
     *
     * @param institution the institution to store
     * @return the stored institution
     */
    Institution save(Institution institution);
}
