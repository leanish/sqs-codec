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
    private final StandardBase64Encoder standardEncoder = new StandardBase64Encoder();
    private final NoOpEncoder noOpEncoder = new NoOpEncoder();

    // just a few tests as SqsPayloadCodecInterceptorIntegrationTest covers most cases already
    @Test
    void noOp() {
        String payload = "payload-42";

        byte[] encoded = noOpEncoder.encode(payload.getBytes(StandardCharsets.UTF_8));
        byte[] decoded = noOpEncoder.decode(encoded);

        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .isEqualTo(payload);
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

    @Test
    void base64Standard_invalid() {
        assertThatThrownBy(() -> standardEncoder.decode("[]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void base64_variantsDifferences() {
        byte[] payload = new byte[] {(byte) 0xfb, (byte) 0xef, (byte) 0xff};

        assertThat(new String(urlEncoder.encode(payload), StandardCharsets.UTF_8)).isEqualTo("--__");
        assertThat(new String(standardEncoder.encode(payload), StandardCharsets.UTF_8)).isEqualTo("++//");
    }
}
