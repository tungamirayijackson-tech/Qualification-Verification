package zw.ac.qvs.credential.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.credential.domain.Qualification;

/** Outbound port for qualifications an institution offers. */
public interface QualificationRepository {

    Optional<Qualification> findById(UUID id);

    List<Qualification> findByInstitution(UUID institutionId);

    Qualification save(Qualification qualification);
}
