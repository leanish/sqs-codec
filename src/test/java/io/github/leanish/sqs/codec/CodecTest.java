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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionLevel;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import io.github.leanish.sqs.codec.algorithms.UnsupportedAlgorithmException;
import io.github.leanish.sqs.codec.algorithms.encoding.InvalidPayloadException;

class CodecTest {

    @Test
    void encode_default() {
        Codec codec = new Codec(CompressionAlgorithm.NONE);
        String payload = "payload-42";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        byte[] encoded = codec.encode(payloadBytes);
        byte[] decoded = codec.decode(encoded);

        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .isEqualTo(payload);
        assertThat(new String(decoded, StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    @Test
    void encode_happyCase() {
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);
        String payload = "{\"value\":42}";
        byte[] encoded = codec.encode(payload.getBytes(StandardCharsets.UTF_8));

        String decoded = new String(codec.decode(encoded), StandardCharsets.UTF_8);

        assertThat(decoded)
                .isEqualTo(payload);
    }

    @Test
    void encode_respectsConfiguredCompressionLevel() {
        String payload = "compression-level-".repeat(512);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Codec minimumCodec = new Codec(CompressionAlgorithm.GZIP, CompressionLevel.MINIMUM);
        Codec maximumCodec = new Codec(CompressionAlgorithm.GZIP, CompressionLevel.MAXIMUM);

        assertThat(minimumCodec.encode(payloadBytes))
                .isNotEqualTo(maximumCodec.encode(payloadBytes));
    }

    @Test
    void encode_usesAlgorithmDefaultWhenCompressionLevelIsUnset() {
        String payload = "compression-level-".repeat(512);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        Codec defaultCodec = new Codec(CompressionAlgorithm.GZIP);
        Codec unsetLevelCodec = new Codec(CompressionAlgorithm.GZIP, (CompressionLevel) null);

        assertThat(unsetLevelCodec.encode(payloadBytes))
                .isEqualTo(defaultCodec.encode(payloadBytes));
    }

    @Test
    void encode_rejectsConfiguredCompressionLevelForSnappy() {
        assertThatThrownBy(() -> new Codec(CompressionAlgorithm.SNAPPY, CompressionLevel.HIGH))
                .isInstanceOf(UnsupportedAlgorithmException.class)
                .hasMessage("Compression level HIGH is not supported for compression algorithm snappy");
    }

    @Test
    void encode_happyCase_withExplicitAscii85() {
        Codec codec = new Codec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.ASCII85);
        String payload = "{\"value\":42}";
        byte[] encoded = codec.encode(payload.getBytes(StandardCharsets.UTF_8));

        String decoded = new String(codec.decode(encoded), StandardCharsets.UTF_8);

        assertThat(decoded)
                .isEqualTo(payload);
    }

    @Test
    void encode_happyCase_withCompressionLevelAndExplicitAscii85() {
        Codec codec = new Codec(CompressionAlgorithm.GZIP, CompressionLevel.HIGH, EncodingAlgorithm.ASCII85);
        String payload = "{\"value\":42}";
        byte[] encoded = codec.encode(payload.getBytes(StandardCharsets.UTF_8));

        String decoded = new String(codec.decode(encoded), StandardCharsets.UTF_8);

        assertThat(decoded)
                .isEqualTo(payload);
    }

    @ParameterizedTest
    @EnumSource(value = EncodingAlgorithm.class, names = {"BASE64", "ASCII85"})
    void encode_encodingOnly(EncodingAlgorithm encodingAlgorithm) {
        Codec codec = new Codec(CompressionAlgorithm.NONE, encodingAlgorithm);
        String payload = "{\"value\":42}";
        byte[] encoded = codec.encode(payload.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .isNotEqualTo(payload);
        assertThat(new String(codec.decode(encoded), StandardCharsets.UTF_8))
                .isEqualTo(payload);
    }

    @Test
    void decode_invalidBase64() {
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);

        assertThatThrownBy(() -> codec.decode("!!!".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }
}
