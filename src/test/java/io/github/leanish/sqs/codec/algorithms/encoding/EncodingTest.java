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

class EncodingTest {

    private final Base64Encoder urlEncoder = new Base64Encoder();

    @Test
    void base64_roundTrip() {
        String payload = "{\"value\":42}";
        byte[] encoded = urlEncoder.encode(payload.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = urlEncoder.decode(encoded);

        assertThat(new String(decoded, StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    @Test
    void base64_invalidPayload() {
        assertThatThrownBy(() -> urlEncoder.decode("!@#".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
