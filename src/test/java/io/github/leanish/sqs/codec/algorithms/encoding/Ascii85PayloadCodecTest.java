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
