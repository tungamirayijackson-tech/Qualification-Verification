package zw.ac.qvs;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Qualification Verification System.
 *
 * <p>One deployable service, five internally enforced modules (credential, verification,
 * ledger, identity, shared). The boundaries are checked by ArchUnit rather than by
 * convention, so the structure cannot silently rot.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class QvsApplication {

    public static void main(String[] args) {
        SpringApplication.run(QvsApplication.class, args);
    }
}
