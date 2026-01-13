/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import io.github.leanish.sqs.codec.algorithms.encoding.InvalidPayloadException;

class PayloadCodecTest {

    @Test
    void encode_default() {
        PayloadCodec codec = new PayloadCodec();
        String payload = "payload-42";

        byte[] encoded = codec.encode(payload.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .isEqualTo(payload);
        assertThat(new String(codec.decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    @Test
    void encode_happyCase() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
        String payload = "{\"value\":42}";
        byte[] encoded = codec.encode(payload.getBytes(StandardCharsets.UTF_8));

        String decoded = new String(codec.decode(encoded), StandardCharsets.UTF_8);

        assertThat(decoded)
                .isEqualTo(payload);
    }

    @Test
    void decode_invalidBase64() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.NONE, EncodingAlgorithm.BASE64);

        assertThatThrownBy(() -> codec.decode("!!!".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
