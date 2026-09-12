package zw.ac.qvs.credential.adapter;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zw.ac.qvs.credential.application.AddQualification;
import zw.ac.qvs.credential.application.CredentialRepository;
import zw.ac.qvs.credential.application.IssueSigningKey;
import zw.ac.qvs.credential.application.HolderRepository;
import zw.ac.qvs.credential.application.InstitutionRepository;
import zw.ac.qvs.credential.application.ListInstitutions;
import zw.ac.qvs.credential.application.OnboardInstitution;
import zw.ac.qvs.credential.application.QualificationRepository;
import zw.ac.qvs.credential.application.RegisterCredential;
import zw.ac.qvs.credential.application.AtomicRegistration;
import zw.ac.qvs.credential.application.CredentialSearchRepository;
import zw.ac.qvs.credential.application.ImportCohort;
import zw.ac.qvs.credential.application.RevokeCredential;
import zw.ac.qvs.credential.application.SearchCredentials;
import zw.ac.qvs.ledger.application.AppendEntry;
import zw.ac.qvs.shared.adapter.QvsProperties;
import zw.ac.qvs.verification.application.CredentialSigner;
import zw.ac.qvs.verification.application.KeyVault;

/**
 * Wires the credential module's use cases.
 *
 * <p>Use cases are plain classes with constructor dependencies rather than {@code @Service}
 * beans. That keeps the application layer free of Spring annotations and unit-testable with a
 * plain {@code new}, which is what makes the coverage gate cheap to hold rather than painful.
 */
@Configuration
public class CredentialModuleConfiguration {

    @Bean
    public ListInstitutions listInstitutions(InstitutionRepository repository, Clock clock) {
        return new ListInstitutions(repository, clock);
    }

    @Bean
    public RegisterCredential registerCredential(
            InstitutionRepository institutions,
            QualificationRepository qualifications,
            HolderRepository holders,
            CredentialRepository credentials,
            KeyVault keyVault,
            CredentialSigner signer,
            AppendEntry ledger,
            Clock clock,
            QvsProperties properties) {
        return new RegisterCredential(institutions, qualifications, holders, credentials,
                keyVault, signer, ledger, clock, properties.crypto().nationalIdSalt());
    }

    @Bean
    public ImportCohort importCohort(AtomicRegistration registration, Clock clock) {
        return new ImportCohort(registration, clock);
    }

    @Bean
    public SearchCredentials searchCredentials(
            CredentialSearchRepository repository, QvsProperties properties) {
        return new SearchCredentials(repository, properties.crypto().nationalIdSalt());
    }

    /**
     * Revocation, which needs the qualifications to answer "whose credential is this?" — the
     * check that confines a registrar to their own institution's records.
     */
    @Bean
    public RevokeCredential revokeCredential(CredentialRepository credentials,
            QualificationRepository qualifications, AppendEntry ledger, Clock clock) {
        return new RevokeCredential(credentials, qualifications, ledger, clock);
    }

    /**
     * The administrative write side (ADMIN, and qualifications for a registrar too).
     *
     * <p>Assembled here beside the rest of the module so the whole of what the credential module
     * can do is visible in one file, which is the same reason the verification module wires its
     * cryptography explicitly.
     */
    @Bean
    public OnboardInstitution onboardInstitution(
            InstitutionRepository institutions, AppendEntry ledger) {
        return new OnboardInstitution(institutions, ledger);
    }

    @Bean
    public IssueSigningKey issueSigningKey(
            InstitutionRepository institutions,
            KeyVault keyVault,
            AppendEntry ledger,
            java.time.Clock clock) {
        return new IssueSigningKey(institutions, keyVault, ledger, clock);
    }

    @Bean
    public AddQualification addQualification(
            InstitutionRepository institutions,
            QualificationRepository qualifications,
            AppendEntry ledger) {
        return new AddQualification(institutions, qualifications, ledger);
    }
}
