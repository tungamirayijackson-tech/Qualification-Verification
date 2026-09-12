package zw.ac.qvs.verification.adapter;

import java.nio.file.Path;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import zw.ac.qvs.shared.adapter.QvsProperties;
import zw.ac.qvs.shared.domain.FieldCipher;
import zw.ac.qvs.verification.adapter.in.AnomalyScheduler;
import zw.ac.qvs.verification.adapter.out.EncryptedFilePrivateKeyStore;
import zw.ac.qvs.verification.adapter.out.KeyVaultAdapter;
import zw.ac.qvs.verification.adapter.out.NimbusCredentialSigner;
import zw.ac.qvs.verification.adapter.out.JdbcVerificationActivity;
import zw.ac.qvs.verification.adapter.out.PdfBoxReportRenderer;
import zw.ac.qvs.verification.adapter.out.PrivateKeyStore;
import zw.ac.qvs.verification.adapter.out.SigningKeyJpaRepository;
import zw.ac.qvs.verification.adapter.out.VaultReportKeys;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.verification.application.CredentialSigner;
import zw.ac.qvs.verification.application.IssueShareToken;
import zw.ac.qvs.verification.application.KeyVault;
import zw.ac.qvs.verification.application.DetectAnomalies;
import zw.ac.qvs.verification.application.LedgerPresence;
import zw.ac.qvs.verification.application.ProduceVerificationReport;
import zw.ac.qvs.verification.application.ReportKeys;
import zw.ac.qvs.verification.application.ReportRenderer;
import zw.ac.qvs.verification.application.VerificationActivity;
import zw.ac.qvs.verification.application.ShareTokenRepository;
import zw.ac.qvs.verification.application.VerificationLog;
import zw.ac.qvs.verification.application.VerificationReadModel;
import zw.ac.qvs.verification.application.VerifyCredential;

/**
 * Wires the verification module.
 *
 * <p>The cryptographic collaborators are assembled here explicitly rather than discovered by
 * component scanning. That is a deliberate choice for this module in particular: everything an
 * assessor needs in order to audit how credentials are signed — which cipher, which key store,
 * which signer, and where the keys live — is visible in one short file, instead of having to
 * be reconstructed from annotations scattered across a package.
 */
@Configuration
// The anomaly agent is the only scheduled work in this system, so scheduling is switched on
// here beside it rather than on the application class, where it would look like a global
// capability and invite unrelated timers.
@EnableScheduling
public class VerificationModuleConfiguration {

    @Bean
    public FieldCipher fieldCipher(QvsProperties properties) {
        return new FieldCipher(properties.crypto().fieldKey());
    }

    @Bean
    public PrivateKeyStore privateKeyStore(QvsProperties properties, FieldCipher cipher) {
        return new EncryptedFilePrivateKeyStore(Path.of(properties.vault().directory()), cipher);
    }

    @Bean
    public CredentialSigner credentialSigner(PrivateKeyStore privateKeyStore) {
        return new NimbusCredentialSigner(privateKeyStore);
    }

    @Bean
    public KeyVault keyVault(
            SigningKeyJpaRepository repository, PrivateKeyStore privateKeyStore, Clock clock) {
        return new KeyVaultAdapter(repository, privateKeyStore, clock);
    }

    @Bean
    public VerifyCredential verifyCredential(
            VerificationReadModel readModel,
            KeyVault keyVault,
            CredentialSigner signer,
            LedgerPresence ledgerPresence,
            AppendEntry ledger,
            VerificationLog log,
            Clock clock) {
        return new VerifyCredential(
                readModel, keyVault, signer, ledgerPresence, ledger, log, clock);
    }

    /**
     * FR-12. The report key is separate from the institution keys on purpose -- see
     * {@link ReportKeys} -- and the renderer is a port so the application layer does not depend
     * on PDFBox.
     */
    @Bean
    public ReportKeys reportKeys(PrivateKeyStore privateKeyStore) {
        return new VaultReportKeys(privateKeyStore);
    }

    @Bean
    public ReportRenderer reportRenderer() {
        return new PdfBoxReportRenderer();
    }

    @Bean
    public ProduceVerificationReport produceVerificationReport(
            VerifyCredential verifyCredential,
            CredentialSigner signer,
            ReportKeys reportKeys,
            ReportRenderer reportRenderer) {
        return new ProduceVerificationReport(verifyCredential, signer, reportKeys, reportRenderer);
    }

    /**
     * The anomaly agent (bonus). Reads the verification log, reports what stands out, acts on
     * nothing -- see {@link DetectAnomalies} for why that limit is deliberate.
     */
    @Bean
    public VerificationActivity verificationActivity(JdbcTemplate jdbc) {
        return new JdbcVerificationActivity(jdbc);
    }

    @Bean
    public DetectAnomalies detectAnomalies(
            VerificationActivity activity, QvsProperties properties, Clock clock) {
        var configured = properties.anomaly();
        return new DetectAnomalies(activity,
                new zw.ac.qvs.verification.domain.AnomalyRules.Thresholds(
                        configured.unknownTokensPerClient(),
                        configured.distinctCredentialsPerClient()),
                clock);
    }

    @Bean
    public AnomalyScheduler anomalyScheduler(
            DetectAnomalies detectAnomalies, QvsProperties properties, MeterRegistry meters) {
        return new AnomalyScheduler(detectAnomalies,
                Duration.ofMinutes(properties.anomaly().scanWindowMinutes()), meters);
    }

    @Bean
    public IssueShareToken issueShareToken(
            VerificationReadModel readModel,
            ShareTokenRepository tokens,
            AppendEntry ledger,
            Clock clock,
            QvsProperties properties) {
        return new IssueShareToken(readModel, tokens, ledger, clock,
                properties.token().defaultTtlDays(), properties.token().maxTtlDays());
    }
}
