package zw.ac.qvs.credential.application;

import java.util.List;
import zw.ac.qvs.credential.domain.Credential;

/**
 * Registers credentials with explicit control over where the transaction boundaries fall.
 *
 * <p>This port exists because FR-02 asks for two things that pull against each other: <em>a
 * malformed row fails that row only</em>, and <em>a thousand rows in under thirty seconds</em>.
 *
 * <p>A registration writes a credential row and appends a ledger entry, and those must commit
 * together — the ledger port declares {@code Propagation.MANDATORY} to enforce it. Satisfying
 * the isolation clause naively means one transaction per row, and that is what the first
 * implementation did. It also meant a thousand commits, each with its own disk flush, and the
 * measured result was thirty-six seconds against a thirty-second budget.
 *
 * <p>So the import registers a <b>chunk</b> at a time and falls back to one row at a time only
 * for a chunk that failed. A clean file pays ten commits instead of a thousand; a file with one
 * bad row pays those ten plus one chunk replayed individually, and still isolates the failure
 * to its own row. Both methods are here because the import needs both.
 *
 * <p>The application layer states the requirement; the adapter supplies the propagation,
 * because transaction semantics are a framework concern this layer has no business naming.
 */
public interface AtomicRegistration {

    /**
     * Registers one credential in a transaction of its own.
     *
     * @param command the registration
     * @return the signed credential
     */
    Credential registerInOwnTransaction(RegisterCredential.Command command);

    /**
     * Registers several credentials in a single transaction.
     *
     * <p>All or nothing: if any row is refused the whole chunk rolls back, and the caller is
     * expected to replay it one row at a time to find out which.
     *
     * @param commands the registrations
     * @return the signed credentials
     */
    List<Credential> registerChunkInOwnTransaction(List<RegisterCredential.Command> commands);
}
