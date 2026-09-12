package zw.ac.qvs.credential.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.Serial;

/** Outbound port for the register of credentials. */
public interface CredentialRepository {

    Optional<Credential> findBySerial(Serial serial);

    Optional<Credential> findById(UUID id);

    List<Credential> findByHolder(UUID holderId);

    /**
     * Inserts a newly registered credential.
     *
     * <p>Separate from {@link #save} because the caller already knows this row is new, and
     * saying so is worth a round trip. A generic save has to ask the database whether the row
     * exists before it can choose between INSERT and UPDATE — a lookup that is a guaranteed
     * miss on the registration path, and one paid once per row of a cohort import.
     *
     * @param credential the new credential
     * @return the stored credential
     */
    Credential insert(Credential credential);

    /**
     * Updates an existing credential, which in practice means revoking it.
     *
     * @param credential the changed credential
     * @return the stored credential
     */
    Credential save(Credential credential);

    /**
     * Reserves the next per-institution, per-year sequence number for a serial.
     *
     * <p>Must be safe against concurrent registration: two registrars issuing at the same
     * moment must not be handed the same number. The unique constraint on {@code serial} is
     * the backstop, but relying on it alone would turn a routine concurrent write into a
     * user-visible error.
     *
     * @param institutionCode short institution code embedded in the serial
     * @param year            award year
     * @return the next sequence number
     */
    int reserveSerialSequence(String institutionCode, int year);
}
