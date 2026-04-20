/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdIOException;

import io.github.leanish.sqs.codec.algorithms.CompressionLevel;

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
    @MethodSource("gzipLevelCases")
    void configuredLevels_mapToExpectedGzipDeflaterLevels(
            CompressionLevel compressionLevel,
            int expectedLevel) {
        assertThat(GzipCompressor.deflaterLevel(compressionLevel))
                .isEqualTo(expectedLevel);
    }

    @ParameterizedTest
    @MethodSource("zstdLevelCases")
    void configuredLevels_mapToExpectedZstdLevels(
            CompressionLevel compressionLevel,
            int expectedLevel) {
        assertThat(ZstdCompressor.zstdLevel(compressionLevel))
                .isEqualTo(expectedLevel);
    }

    @Test
    void configuredLevels_keepZstdScaleStrictlyIncreasingUntilHigh() {
        assertThat(ZstdCompressor.zstdLevel(CompressionLevel.MINIMUM))
                .isLessThan(ZstdCompressor.zstdLevel(CompressionLevel.LOW));
        assertThat(ZstdCompressor.zstdLevel(CompressionLevel.LOW))
                .isLessThan(ZstdCompressor.zstdLevel(CompressionLevel.MEDIUM));
        assertThat(ZstdCompressor.zstdLevel(CompressionLevel.MEDIUM))
                .isLessThan(ZstdCompressor.zstdLevel(CompressionLevel.HIGH));
        assertThat(ZstdCompressor.zstdLevel(CompressionLevel.HIGH))
                .isLessThanOrEqualTo(ZstdCompressor.zstdLevel(CompressionLevel.MAXIMUM));
    }

    @ParameterizedTest
    @MethodSource("invalidDecompressionCases")
    void decompress_invalidPayload(
            Compressor compressor,
            String payload,
            Class<? extends IOException> expectedCause,
            String expectedMessage) {
        assertThatThrownBy(() -> compressor.decompress(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(CompressionException.class)
                .hasMessage(expectedMessage)
                .hasCauseInstanceOf(expectedCause);
    }

    private static Stream<Compressor> compressorCases() {
        return Stream.concat(
                Arrays.stream(CompressionLevel.values())
                        .<Compressor>map(ZstdCompressor::new),
                Stream.concat(
                        Arrays.stream(CompressionLevel.values())
                                .<Compressor>map(GzipCompressor::new),
                        Stream.of(new SnappyCompressor(), new NoOpCompressor())));
    }

    private static Stream<Arguments> invalidDecompressionCases() {
        return Stream.of(
                Arguments.of(
                        new GzipCompressor(),
                        "not-gzip",
                        ZipException.class,
                        "Failed to decompress payload with gzip"),
                Arguments.of(
                        new ZstdCompressor(),
                        "not-zstd",
                        ZstdIOException.class,
                        "Failed to decompress payload with zstd"),
                Arguments.of(
                        new SnappyCompressor(),
                        "not-snappy",
                        IOException.class,
                        "Failed to decompress payload with snappy"));
    }

    private static Stream<Arguments> gzipLevelCases() {
        return Stream.of(
                Arguments.of(CompressionLevel.MINIMUM, Deflater.BEST_SPEED),
                Arguments.of(CompressionLevel.LOW, 3),
                Arguments.of(CompressionLevel.MEDIUM, Deflater.DEFAULT_COMPRESSION),
                Arguments.of(CompressionLevel.HIGH, 8),
                Arguments.of(CompressionLevel.MAXIMUM, Deflater.BEST_COMPRESSION));
    }

    private static Stream<Arguments> zstdLevelCases() {
        return Stream.of(
                Arguments.of(CompressionLevel.MINIMUM, 1),
                Arguments.of(CompressionLevel.LOW, 2),
                Arguments.of(CompressionLevel.MEDIUM, Zstd.defaultCompressionLevel()),
                Arguments.of(CompressionLevel.HIGH, 16),
                Arguments.of(CompressionLevel.MAXIMUM, Zstd.maxCompressionLevel()));
    }
}
