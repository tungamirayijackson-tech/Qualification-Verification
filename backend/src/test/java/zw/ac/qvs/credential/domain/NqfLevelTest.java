package zw.ac.qvs.credential.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NqfLevelTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 9, 10})
    @DisplayName("accepts every level the framework defines")
    void acceptsValidLevels(int level) {
        assertThat(new NqfLevel(level).value()).isEqualTo(level);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 11, 99, Integer.MAX_VALUE})
    @DisplayName("an out-of-range level cannot be constructed at all")
    void rejectsInvalidLevels(int level) {
        assertThatThrownBy(() -> new NqfLevel(level))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NQF level");
    }

    @Test
    @DisplayName("levels 8 and above are postgraduate")
    void postgraduateBoundary() {
        assertThat(new NqfLevel(7).isPostgraduate()).isFalse();
        assertThat(new NqfLevel(8).isPostgraduate()).isTrue();
        assertThat(new NqfLevel(10).isPostgraduate()).isTrue();
    }
}
