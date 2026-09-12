package zw.ac.qvs.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstitutionTest {

    private static final LocalDate LAPSES = LocalDate.of(2026, 12, 31);

    private static Institution institution(LocalDate accreditedUntil, String keyId) {
        return new Institution(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                "Example University",
                "ZW",
                "PR-0142",
                accreditedUntil,
                keyId);
    }

    @Test
    @DisplayName("accreditation is inclusive of its final day")
    void accreditationIsInclusive() {
        Institution subject = institution(LAPSES, "inst-0142-2026-a");

        assertThat(subject.wasAccreditedOn(LAPSES.minusDays(1))).isTrue();
        assertThat(subject.wasAccreditedOn(LAPSES)).isTrue();
        assertThat(subject.wasAccreditedOn(LAPSES.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("standing is judged on the award date, not today")
    void standingIsPointInTime() {
        // The reason this matters: a 2020 degree from an institution whose accreditation
        // lapsed in 2024 is still a valid award. FR-05 check 2 asks about the award date.
        Institution lapsed = institution(LocalDate.of(2024, 1, 31), "inst-0999-2020-a");

        assertThat(lapsed.wasAccreditedOn(LocalDate.of(2020, 4, 11))).isTrue();
        assertThat(lapsed.canIssueOn(LocalDate.of(2026, 9, 5))).isFalse();
    }

    @Test
    @DisplayName("issuing needs current accreditation and a signing key")
    void issuingNeedsBoth() {
        LocalDate today = LocalDate.of(2026, 9, 5);

        assertThat(institution(LAPSES, "inst-0142-2026-a").canIssueOn(today)).isTrue();
        assertThat(institution(LAPSES, null).canIssueOn(today)).isFalse();
        assertThat(institution(LAPSES, "   ").canIssueOn(today)).isFalse();
        assertThat(institution(today.minusDays(1), "inst-0142-2026-a").canIssueOn(today)).isFalse();
    }

    @Test
    @DisplayName("rejects a malformed institution outright")
    void invariants() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> new Institution(id, "  ", "ZW", "PR-1", LAPSES, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(() -> new Institution(id, "X", "ZAF", "PR-1", LAPSES, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("country");
        assertThatThrownBy(() -> new Institution(id, "X", "ZW", "PR-1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accreditedUntil");
    }

    @Test
    @DisplayName("a null date is an error, not a false verdict")
    void nullDateRejected() {
        assertThatThrownBy(() -> institution(LAPSES, "k").wasAccreditedOn(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
