/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.zip.ZipException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.luben.zstd.ZstdIOException;

class CompressionTest {

    @ParameterizedTest
    @MethodSource("compressorCases")
    void compress_happyCase(Compressor compressor) {
        byte[] payload = "payload-42".getBytes(StandardCharsets.UTF_8);

        byte[] compressed = compressor.compress(payload);
        byte[] decoded = compressor.decompress(compressed);

        assertThat(decoded)
                .isEqualTo(payload);
    }

    @Test
    void compress_noOp() {
        NoOpCompressor compressor = new NoOpCompressor();
        byte[] payload = "payload-42".getBytes(StandardCharsets.UTF_8);

        assertThat(compressor.compress(payload))
                .isSameAs(payload);
        assertThat(compressor.decompress(payload))
                .isSameAs(payload);
    }

    @ParameterizedTest
    @MethodSource("invalidDecompressionCases")
    void decompress_invalidPayload(
            Compressor compressor,
            String payload,
            Class<? extends IOException> expectedCause) {
        assertThatThrownBy(() -> compressor.decompress(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(expectedCause);
    }

    private static Stream<Compressor> compressorCases() {
        return Stream.of(
                new ZstdCompressor(),
                new SnappyCompressor(),
                new GzipCompressor(),
                new NoOpCompressor());
    }

    private static Stream<Arguments> invalidDecompressionCases() {
        return Stream.of(
                Arguments.of(new GzipCompressor(), "not-gzip", ZipException.class),
                Arguments.of(new ZstdCompressor(), "not-zstd", ZstdIOException.class),
                Arguments.of(new SnappyCompressor(), "not-snappy", IOException.class));
    }
}
