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

class Base64CodecTest {

    @Test
    void base64_roundTrip() {
        Base64Codec base64Codec = Base64Codec.instance();

        String payload = "{\"value\":42}";
        byte[] encoded = base64Codec.encode(payload.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = base64Codec.decode(encoded);

        assertThat(new String(decoded, StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    @Test
    void base64_invalidPayload() {
        assertThatThrownBy(() -> Base64Codec.instance().decode("!@#".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
