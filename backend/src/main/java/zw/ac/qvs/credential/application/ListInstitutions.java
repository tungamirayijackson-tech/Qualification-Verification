package zw.ac.qvs.credential.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import zw.ac.qvs.credential.domain.Institution;

/**
 * Use case: list the awarding bodies the register knows about.
 *
 * <p>The walking-skeleton slice. It is thin on purpose, but it is a real one: controller to
 * use case to port to a Flyway-migrated table, so week one proves the whole stack is wired
 * before any feature depends on it.
 */
public class ListInstitutions {

    private final InstitutionRepository repository;
    private final Clock clock;

    /**
     * Creates the use case.
     *
     * @param repository outbound port
     * @param clock      injected so accreditation logic is testable without freezing wall time
     */
    public ListInstitutions(InstitutionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Returns all institutions.
     *
     * @return every registered institution
     */
    public List<Institution> all() {
        return repository.findAll();
    }

    /**
     * Returns only institutions that may register credentials today.
     *
     * @return institutions with current accreditation and a signing key
     */
    public List<Institution> eligibleToIssue() {
        LocalDate today = LocalDate.now(clock);
        return repository.findAll().stream()
                .filter(institution -> institution.canIssueOn(today))
                .toList();
    }
}
