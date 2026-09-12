package zw.ac.qvs.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The four roles and the scoping rules that make them mean something (FR-10).
 */
@Requirement("FR-10")
class UserAccountTest {

    private static final UUID ID = UUID.randomUUID();
    private static final UUID INSTITUTION = UUID.randomUUID();
    private static final String HASH = "$2a$12$abcdefghijklmnopqrstuv";

    private static UserAccount account(Role role, UUID institutionId) {
        return new UserAccount(ID, "someone@example.ac.zw", "Someone", role, institutionId,
                HASH, null, false, false);
    }

    @Test
    @DisplayName("a registrar must be bound to exactly one institution")
    void registrarNeedsAnInstitution() {
        // A registrar with no institution is a registrar who could act for anyone, which is
        // precisely what the trust model forbids.
        assertThatThrownBy(() -> account(Role.REGISTRAR, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound to one institution");

        assertThat(account(Role.REGISTRAR, INSTITUTION).institutionId()).isEqualTo(INSTITUTION);
    }

    @Test
    @DisplayName("an auditor must not be scoped to one institution")
    void auditorIsCrossInstitution() {
        // Pinning an auditor to a single institution would quietly narrow the scope of every
        // audit they ever run, and nothing about the resulting report would say so.
        assertThatThrownBy(() -> account(Role.AUDITOR, INSTITUTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not scoped to an institution");

        assertThat(account(Role.AUDITOR, null).institutionId()).isNull();
    }

    @Test
    @DisplayName("verifiers and admins may be scoped either way")
    void unconstrainedRoles() {
        assertThat(account(Role.VERIFIER, null).role()).isEqualTo(Role.VERIFIER);
        assertThat(account(Role.VERIFIER, INSTITUTION).role()).isEqualTo(Role.VERIFIER);
        assertThat(account(Role.ADMIN, null).role()).isEqualTo(Role.ADMIN);
    }

    @Test
    @DisplayName("the roles that can change the register require a second factor")
    void mfaIsRequiredForWriters() {
        // A leaked password for a registrar or an admin is a forged credential. For the other
        // two it is a read of data the holder was already entitled to read.
        assertThat(Role.REGISTRAR.requiresMfa()).isTrue();
        assertThat(Role.ADMIN.requiresMfa()).isTrue();
        assertThat(Role.AUDITOR.requiresMfa()).isFalse();
        assertThat(Role.VERIFIER.requiresMfa()).isFalse();
    }

    @Test
    @DisplayName("a writer cannot sign in until enrolled; a reader can")
    void signInEligibility() {
        assertThat(account(Role.REGISTRAR, INSTITUTION).canSignIn()).isFalse();
        assertThat(account(Role.AUDITOR, null).canSignIn()).isTrue();

        UserAccount enrolled = new UserAccount(ID, "r@example.ac.zw", "R", Role.REGISTRAR,
                INSTITUTION, HASH, "SECRET", true, false);
        assertThat(enrolled.canSignIn()).isTrue();
        assertThat(enrolled.requiresSecondFactor()).isTrue();
    }

    @Test
    @DisplayName("a disabled account cannot sign in whatever its role")
    void disabledAccounts() {
        UserAccount disabled = new UserAccount(ID, "a@example.ac.zw", "A", Role.AUDITOR, null,
                HASH, null, false, true);

        assertThat(disabled.canSignIn()).isFalse();
    }

    @Test
    @DisplayName("a half-enrolled account does not count as having a second factor")
    void halfEnrolled() {
        // Flag set but no secret: nothing could be verified against it, so treating this as
        // enrolled would let an account through with only a password.
        UserAccount noSecret = new UserAccount(ID, "r@example.ac.zw", "R", Role.REGISTRAR,
                INSTITUTION, HASH, null, true, false);

        assertThat(noSecret.requiresSecondFactor()).isFalse();
    }

    @Test
    @DisplayName("refuses a malformed account")
    void invariants() {
        assertThatThrownBy(() -> new UserAccount(ID, "not-an-address", "X", Role.AUDITOR, null,
                HASH, null, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("email");

        assertThatThrownBy(() -> new UserAccount(ID, "a@b.c", "X", null, null,
                HASH, null, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("role");

        assertThatThrownBy(() -> new UserAccount(ID, "a@b.c", "X", Role.AUDITOR, null,
                " ", null, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password hash");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("every role maps to a prefixed Spring Security authority")
    void authorities(Role role) {
        assertThat(role.authority()).isEqualTo("ROLE_" + role.name());
    }

    @Test
    @DisplayName("only the registrar is institution-scoped")
    void scoping() {
        assertThat(Role.REGISTRAR.isInstitutionScoped()).isTrue();
        assertThat(Role.AUDITOR.isInstitutionScoped()).isFalse();
        assertThat(Role.ADMIN.isInstitutionScoped()).isFalse();
        assertThat(Role.VERIFIER.isInstitutionScoped()).isFalse();
    }
}
