package zw.ac.qvs.credential.application;

import java.util.Optional;
import java.util.UUID;
import zw.ac.qvs.credential.domain.Holder;

/**
 * Outbound port for holders.
 *
 * <p>Lookup is by the salted hash of a national ID, never by the ID itself. The plaintext
 * identifier does not exist anywhere in this interface, which is the cheapest way to guarantee
 * it does not end up in a query log.
 */
public interface HolderRepository {

    Optional<Holder> findByNationalIdHash(String nationalIdHash);

    Optional<Holder> findById(UUID id);

    /**
     * Stores a holder, creating or updating the encrypted name and search column.
     *
     * @param holder the holder
     * @return the stored holder
     */
    Holder save(Holder holder);

    /**
     * Inserts a holder the caller has just created.
     *
     * <p>Same reasoning as {@code CredentialRepository.insert}: the caller reached here through
     * a lookup that already missed, so asking the database again whether the row exists is a
     * round trip spent confirming something known.
     *
     * @param holder the new holder
     * @return the stored holder
     */
    Holder insert(Holder holder);
}
