package zw.ac.qvs.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cipher that protects holder names and private key material at rest.
 *
 * <p>The tamper test is the one that matters. AES-GCM is authenticated, so a modified
 * ciphertext must <em>fail</em> rather than decrypt to rubbish — and a silently corrupted
 * holder name would flow straight into a signature.
 */
class FieldCipherTest {

    private final FieldCipher cipher = new FieldCipher(FieldCipher.generateKey());

    @Test
    @DisplayName("round-trips text, including non-ASCII")
    void roundTrip() {
        assertThat(cipher.decrypt(cipher.encrypt("Thandeka N. Mahlangu")))
                .isEqualTo("Thandeka N. Mahlangu");
        assertThat(cipher.decrypt(cipher.encrypt("Renée Ngũgĩ wa Thiong'o")))
                .isEqualTo("Renée Ngũgĩ wa Thiong'o");
        assertThat(cipher.decrypt(cipher.encrypt(""))).isEmpty();
    }

    @Test
    @DisplayName("produces different ciphertext each time, because the IV is fresh")
    void ivIsNeverReused() {
        // Reusing an IV with the same key in GCM leaks the XOR of two plaintexts and allows
        // forgery, so identical input producing identical output would be a serious defect
        // rather than a curiosity.
        String first = cipher.encrypt("same input");
        String second = cipher.encrypt("same input");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(cipher.decrypt(second));
    }

    @Test
    @DisplayName("refuses to decrypt ciphertext that has been altered")
    void detectsTampering() {
        String sealed = cipher.encrypt("BSc Computer Science");
        byte[] raw = Base64.getDecoder().decode(sealed);
        raw[raw.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(raw);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decryption failed");
    }

    @Test
    @DisplayName("a different key cannot read the ciphertext")
    void wrongKey() {
        String sealed = cipher.encrypt("secret");
        FieldCipher other = new FieldCipher(FieldCipher.generateKey());

        assertThatThrownBy(() -> other.decrypt(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("rejects a key that is not 256 bits of base64")
    void keyValidation() {
        assertThatThrownBy(() -> new FieldCipher(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldCipher("  "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FieldCipher("not base64 at all!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base64");
        assertThatThrownBy(() -> new FieldCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("generated keys are 256 bits and distinct")
    void generatedKeys() {
        String key = FieldCipher.generateKey();

        assertThat(Base64.getDecoder().decode(key)).hasSize(32);
        assertThat(key).isNotEqualTo(FieldCipher.generateKey());
    }

    @Test
    @DisplayName("rejects malformed input rather than failing obscurely later")
    void malformedInput() {
        assertThatThrownBy(() -> cipher.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.decrypt("!!!not base64!!!"))
                .isInstanceOf(IllegalStateException.class);
        // Long enough to be base64, too short to contain an IV and a tag.
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(new byte[4])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }
}
