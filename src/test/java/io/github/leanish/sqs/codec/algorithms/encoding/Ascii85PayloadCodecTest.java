/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.encoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Ascii85PayloadCodecTest {

    @Test
    void ascii85_roundTrip() {
        Ascii85PayloadCodec ascii85Codec = Ascii85PayloadCodec.instance();

        String payload = "{\"value\":42}";
        byte[] encoded = ascii85Codec.encode(payload.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = ascii85Codec.decode(encoded);

        assertThat(new String(decoded, StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    @Test
    void ascii85_knownValues() {
        Ascii85PayloadCodec ascii85Codec = Ascii85PayloadCodec.instance();

        assertThat(ascii85Codec.encodeToString("Man ".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("9jqo^");
        assertThat(ascii85Codec.encodeToString(new byte[] {0, 0, 0, 0}))
                .isEqualTo("!!!!!");
        assertThat(ascii85Codec.encodeToString(new byte[] {0}))
                .isEqualTo("!!");
        assertThat(ascii85Codec.encodeToString(new byte[] {0, 0}))
                .isEqualTo("!!!");
        assertThat(ascii85Codec.encodeToString(new byte[] {0, 0, 0}))
                .isEqualTo("!!!!");
        assertThat(new String(ascii85Codec.decode("9jqo^".getBytes(StandardCharsets.US_ASCII)), StandardCharsets.US_ASCII))
                .isEqualTo("Man ");
    }

    @Test
    void ascii85_maximumChunkRoundTrip() {
        Ascii85PayloadCodec ascii85Codec = Ascii85PayloadCodec.instance();
        byte[] maximumChunk = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff};

        assertThat(ascii85Codec.encodeToString(maximumChunk))
                .isEqualTo("s8W-!");
        assertThat(ascii85Codec.decode("s8W-!".getBytes(StandardCharsets.US_ASCII)))
                .containsExactly(maximumChunk);
    }

    @Test
    void ascii85_emptyPayload() {
        Ascii85PayloadCodec ascii85Codec = Ascii85PayloadCodec.instance();

        assertThat(ascii85Codec.encode(new byte[0]))
                .isEmpty();
        assertThat(ascii85Codec.decode(new byte[0]))
                .isEmpty();
    }

    @Test
    void ascii85_rejectsNonCanonicalPayload() {
        assertThatThrownBy(() -> Ascii85PayloadCodec.instance().decode("z".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid ascii85 payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ascii85_rejectsOverflowingChunk() {
        assertThatThrownBy(() -> Ascii85PayloadCodec.instance().decode("uuuuu".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid ascii85 payload")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ASCII85 chunk exceeds 32-bit range");
    }

    @Test
    void ascii85_rejectsOverflowingTrailingChunk() {
        assertThatThrownBy(() -> Ascii85PayloadCodec.instance().decode("uuuu".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid ascii85 payload")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ASCII85 chunk exceeds 32-bit range");
    }

    @ParameterizedTest
    @ValueSource(strings = {"!\"", "!;", "!!\"", "!!!\"", "9jqo^!\""})
    void ascii85_rejectsNonCanonicalFinalGroup(String encoded) {
        // Each final group decodes to zero bytes, whose canonical form is all '!'.
        assertThatThrownBy(() -> Ascii85PayloadCodec.instance().decode(encoded.getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid ascii85 payload")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ASCII85 payload has a non-canonical final group");
    }

    @Test
    void ascii85_acceptsEveryCanonicalFinalGroup() {
        Ascii85PayloadCodec ascii85Codec = Ascii85PayloadCodec.instance();

        for (int value = 0; value < 1 << 16; value++) {
            byte[] oneByte = {(byte) value};
            byte[] twoBytes = {(byte) (value >> 8), (byte) value};
            byte[] threeBytes = {(byte) (value >> 8), (byte) value, (byte) (value * 31)};
            assertThat(ascii85Codec.decode(ascii85Codec.encode(oneByte))).as("%s", value).containsExactly(oneByte);
            assertThat(ascii85Codec.decode(ascii85Codec.encode(twoBytes))).as("%s", value).containsExactly(twoBytes);
            assertThat(ascii85Codec.decode(ascii85Codec.encode(threeBytes))).as("%s", value).containsExactly(threeBytes);
        }
    }

    @Test
    void ascii85_rejectsSingleCharacterTail() {
        assertThatThrownBy(() -> Ascii85PayloadCodec.instance().decode("u".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid ascii85 payload")
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ASCII85 payload cannot end with a single character");
    }
}
