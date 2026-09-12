package zw.ac.qvs.ledger.adapter;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.ledger.application.ExportTrail;
import zw.ac.qvs.ledger.application.LedgerRepository;
import zw.ac.qvs.ledger.application.VerifyChain;

/** Wires the ledger module's use cases. */
@Configuration
public class LedgerModuleConfiguration {

    @Bean
    public AppendEntry appendEntry(LedgerRepository ledger, Clock clock) {
        return new AppendEntry(ledger, clock);
    }

    @Bean
    public VerifyChain verifyChain(LedgerRepository ledger) {
        return new VerifyChain(ledger);
    }

    @Bean
    public ExportTrail exportTrail(LedgerRepository ledger, AppendEntry append, Clock clock) {
        return new ExportTrail(ledger, append, clock);
    }
}
