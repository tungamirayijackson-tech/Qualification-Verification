package zw.ac.qvs.shared.adapter;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The system's own configuration, bound once and validated at startup.
 *
 * <p>Every value here is a secret or a path, which is exactly why they are bound to a typed
 * record with validation rather than read with {@code @Value} wherever they happen to be
 * needed. A missing encryption key should stop the application from starting, loudly, at
 * deploy time — not surface three days later as a decryption failure on one holder record.
 *
 * <p>None of these have defaults in the repository. There is nothing to accidentally ship.
 *
 * @param vault     private signing key custody
 * @param crypto    field-level encryption and hashing secrets
 * @param token     share-token behaviour
 * @param rateLimit allowances on the public surface (FR-11)
 * @param anomaly   what the anomaly agent considers unusual
 * @param bootstrap the first administrator, for a register that has no accounts yet
 */
@ConfigurationProperties(prefix = "qvs")
@Validated
public record QvsProperties(
        Vault vault,
        Crypto crypto,
        Token token,
        RateLimit rateLimit,
        Anomaly anomaly,
        Bootstrap bootstrap) {

    /**
     * The first administrator, created only into a register that holds no accounts.
     *
     * <p>Both values are optional, and absent is a legitimate state: a system that already has
     * accounts needs neither, and leaving them set afterwards would be leaving a credential in
     * the environment for no reason. When the register is empty and these are absent, start-up
     * says so loudly rather than coming up unusable and silent.
     *
     * @param adminEmail    the address the first administrator signs in with
     * @param adminPassword their initial password; used once, stored only as a hash
     */
    public record Bootstrap(String adminEmail, String adminPassword) {

        /** Whether there is enough here to create the account. */
        public boolean isComplete() {
            return adminEmail != null && !adminEmail.isBlank()
                    && adminPassword != null && !adminPassword.isBlank();
        }
    }

    /**
     * Thresholds for the anomaly agent.
     *
     * <p>Configuration rather than constants because the right value is a property of the
     * deployment. A national register serving thousands of employers a day and one university's
     * demo do not share a definition of "a lot of checks from one address", and a number
     * compiled into the detector would be wrong in one of them.
     *
     * @param scanWindowMinutes    how far back a scheduled scan looks
     * @param unknownTokensPerClient unknown-token checks from one address before it is odd
     * @param distinctCredentialsPerClient different credentials from one address before it is odd
     */
    public record Anomaly(
            int scanWindowMinutes,
            long unknownTokensPerClient,
            long distinctCredentialsPerClient) {
    }

    /**
     * Where private signing material lives.
     *
     * @param directory filesystem path holding encrypted private keys
     */
    public record Vault(@NotBlank String directory) {
    }

    /**
     * Secrets for field encryption and for salting hashes.
     *
     * @param fieldKey    base64-encoded 256-bit AES key protecting holder names and private keys
     * @param nationalIdSalt salt mixed into the national-ID hash, so the hash cannot be
     *                       attacked with a precomputed table of all valid ID numbers
     * @param ipSalt      salt for client-IP hashes in the verification log
     * @param jwtSecret   HMAC key for access tokens; at least 32 bytes, or HS256 is HS256 in
     *                    name only
     */
    public record Crypto(
            @NotBlank String fieldKey,
            @NotBlank String nationalIdSalt,
            @NotBlank String ipSalt,
            @NotBlank String jwtSecret) {
    }

    /**
     * Allowances on the public surface.
     *
     * <p>Two numbers rather than one, because the surfaces cost very different amounts. A
     * verification is a few queries and a signature check; rendering a signed PDF report is
     * orders of magnitude more work, so a limit generous enough for the first would leave the
     * second wide open to being used as a CPU amplifier.
     *
     * @param verifyPerMinute public verifications permitted per address per minute
     * @param reportPerMinute report generations permitted per address per minute
     */
    public record RateLimit(long verifyPerMinute, long reportPerMinute) {

        public RateLimit {
            if (verifyPerMinute <= 0 || reportPerMinute <= 0) {
                throw new IllegalArgumentException("rate limits must be positive");
            }
            if (reportPerMinute > verifyPerMinute) {
                // Not a hard requirement, but almost certainly a configuration mistake: every
                // report is preceded by a verification, so a larger report allowance can never
                // actually be reached.
                throw new IllegalArgumentException(
                        "the report allowance cannot exceed the verification allowance");
            }
        }
    }

    /**
     * Share-token policy.
     *
     * @param defaultTtlDays how long a freshly minted share token lasts
     * @param maxTtlDays     the longest a registrar may make one
     */
    public record Token(int defaultTtlDays, int maxTtlDays) {

        public Token {
            if (defaultTtlDays <= 0 || maxTtlDays <= 0) {
                throw new IllegalArgumentException("token lifetimes must be positive");
            }
            if (defaultTtlDays > maxTtlDays) {
                throw new IllegalArgumentException(
                        "the default token lifetime cannot exceed the maximum");
            }
        }
    }
}
