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

class Base64PayloadCodecTest {

    @Test
    void base64_roundTrip() {
        Base64PayloadCodec base64Codec = Base64PayloadCodec.instance();

        String payload = "{\"value\":42}";
        byte[] encoded = base64Codec.encode(payload.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = base64Codec.decode(encoded);

        assertThat(new String(decoded, StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    @Test
    void base64_encodeToString() {
        assertThat(Base64PayloadCodec.instance().encodeToString("payload-42".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("cGF5bG9hZC00Mg");
    }

    @Test
    void base64_invalidPayload() {
        assertThatThrownBy(() -> Base64PayloadCodec.instance().decode("!@#".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
