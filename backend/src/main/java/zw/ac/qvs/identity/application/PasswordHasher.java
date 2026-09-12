package zw.ac.qvs.identity.application;

/**
 * Outbound port for password hashing.
 *
 * <p>A port rather than a direct call to a library, so the application layer stays free of
 * Spring Security and the choice of algorithm lives in one adapter that can be changed without
 * touching a use case.
 */
public interface PasswordHasher {

    /**
     * Hashes a password for storage.
     *
     * @param rawPassword the password
     * @return the hash, including its salt and cost parameters
     */
    String hash(String rawPassword);

    /**
     * Checks a password against a stored hash.
     *
     * <p>Implementations must be constant-time with respect to the password, and must return
     * false rather than throwing on a malformed hash.
     *
     * @param rawPassword  the password presented
     * @param storedHash   the stored hash
     * @return true when they match
     */
    boolean matches(String rawPassword, String storedHash);
}
