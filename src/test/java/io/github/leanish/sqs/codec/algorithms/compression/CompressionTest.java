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
        byte[] decoded = compressor.decompress(compressed, Integer.MAX_VALUE);

        assertThat(decoded)
                .isEqualTo(payload);
    }

    @Test
    void compress_noOp() {
        NoOpCompressor compressor = new NoOpCompressor();
        byte[] payload = "payload-42".getBytes(StandardCharsets.UTF_8);

        assertThat(compressor.compress(payload))
                .isSameAs(payload);
        assertThat(compressor.decompress(payload, Integer.MAX_VALUE))
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
        assertThatThrownBy(() -> compressor.decompress(payload.getBytes(StandardCharsets.UTF_8), Integer.MAX_VALUE))
                .isInstanceOf(CompressionException.class)
                .hasMessage(expectedMessage)
                .hasCauseInstanceOf(expectedCause);
    }

    @Test
    void compressionException_compressKeepsAlgorithmAndCause() {
        IOException cause = new IOException("stream closed");

        assertThat(CompressionException.compress("gzip", cause))
                .hasMessage("Failed to compress payload with gzip")
                .hasCause(cause);
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

    private static final int MIB = 1024 * 1024;

    @ParameterizedTest
    @MethodSource("boundedCompressors")
    void decompress_acceptsAPayloadExactlyAtTheLimit_andRefusesOneByteMore(Compressor compressor) {
        byte[] payload = TestPayloadBytes.mixed(100_000);
        byte[] compressed = compressor.compress(payload);

        assertThat(compressor.decompress(compressed, payload.length)).isEqualTo(payload);
        assertThatThrownBy(() -> compressor.decompress(compressed, payload.length - 1))
                .isInstanceOf(DecompressedPayloadTooLargeException.class)
                .hasMessageEndingWith("exceeds " + (payload.length - 1) + " bytes");
    }

    @ParameterizedTest
    @MethodSource("boundedCompressors")
    void decompress_emptyPayloadAtZeroLimit(Compressor compressor) {
        assertThat(compressor.decompress(compressor.compress(new byte[0]), 0)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("boundedCompressors")
    void decompress_negativeLimitRejected(Compressor compressor) {
        byte[] compressed = compressor.compress(new byte[] {1});

        assertThatThrownBy(() -> compressor.decompress(compressed, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxLength must not be negative: -1");
    }

    @ParameterizedTest
    @MethodSource("streamingCompressors")
    void decompress_bombStopsAtTheLimit(Compressor compressor) {
        byte[] bomb = compressor.compress(new byte[64 * MIB]);

        assertThat(bomb.length).isLessThan(MIB / 4);
        assertThatThrownBy(() -> compressor.decompress(bomb, MIB))
                .isInstanceOf(DecompressedPayloadTooLargeException.class);
    }

    @Test
    void decompress_gzipCountsEveryConcatenatedMember() {
        GzipCompressor compressor = new GzipCompressor();
        byte[] member = compressor.compress(new byte[600 * 1024]);
        byte[] concatenated = concat(member, member);

        assertThat(compressor.decompress(concatenated, 1200 * 1024)).hasSize(1200 * 1024);
        assertThatThrownBy(() -> compressor.decompress(concatenated, MIB))
                .isInstanceOf(DecompressedPayloadTooLargeException.class);
    }

    @Test
    void decompress_zstdCountsEveryConcatenatedFrame() {
        ZstdCompressor compressor = new ZstdCompressor();
        byte[] frame = compressor.compress(new byte[600 * 1024]);
        byte[] concatenated = concat(frame, frame);

        assertThat(compressor.decompress(concatenated, 1200 * 1024)).hasSize(1200 * 1024);
        assertThatThrownBy(() -> compressor.decompress(concatenated, MIB))
                .isInstanceOf(DecompressedPayloadTooLargeException.class);
    }

    @Test
    void decompress_gzipStillVerifiesItsTrailerAtTheExactLimit() {
        GzipCompressor compressor = new GzipCompressor();
        byte[] payload = TestPayloadBytes.mixed(10_000);
        byte[] corrupted = compressor.compress(payload);
        // The trailer's last four bytes are the uncompressed size modulo 2^32; its CRC-32 is the four before them.
        corrupted[corrupted.length - 8] ^= 0x01;

        assertThatThrownBy(() -> compressor.decompress(corrupted, payload.length))
                .isInstanceOf(CompressionException.class)
                .isNotInstanceOf(DecompressedPayloadTooLargeException.class)
                .hasMessage("Failed to decompress payload with gzip");
    }

    @Test
    void decompress_snappyChecksTheLengthAValidPayloadDeclares() {
        SnappyCompressor compressor = new SnappyCompressor();
        byte[] compressed = compressor.compress(new byte[2 * MIB]);

        assertThatThrownBy(() -> compressor.decompress(compressed, MIB))
                .isInstanceOf(DecompressedPayloadTooLargeException.class)
                .hasMessage("Decompressed payload with snappy exceeds " + MIB + " bytes");
    }

    @ParameterizedTest
    @MethodSource("zstdCompressors")
    void compress_zstdRecordsTheContentSize(ZstdCompressor compressor) {
        byte[] payload = TestPayloadBytes.mixed(100_000);

        assertThat(Zstd.getFrameContentSize(compressor.compress(payload))).isEqualTo(payload.length);
    }

    @Test
    void decompress_zstdRefusesAFrameWhoseWindowExceedsTheLimit() {
        // A frame header declaring a 128 MiB window (log 27) and no content size, then an empty last raw block.
        byte[] forged = {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0x00, (byte) 0x88, 0x01, 0x00, 0x00};

        assertThat(new ZstdCompressor().decompress(forged, 128 * MIB)).isEmpty();
        assertThatThrownBy(() -> new ZstdCompressor().decompress(forged, MIB))
                .isInstanceOf(DecompressedPayloadTooLargeException.class)
                .hasMessage("Decompressed payload with zstd exceeds " + MIB + " bytes");
    }

    @ParameterizedTest
    @MethodSource("windowLogCases")
    void windowLogFor_holdsTheLimit(int maxLength, int expectedWindowLog) {
        assertThat(ZstdCompressor.windowLogFor(maxLength)).isEqualTo(expectedWindowLog);
    }

    private static Stream<ZstdCompressor> zstdCompressors() {
        return Stream.concat(
                Stream.of(new ZstdCompressor()),
                Arrays.stream(CompressionLevel.values()).map(ZstdCompressor::new));
    }

    private static Stream<Arguments> windowLogCases() {
        return Stream.of(
                Arguments.of(0, 10),
                Arguments.of(12, 10),
                Arguments.of(1024, 10),
                Arguments.of(1025, 11),
                Arguments.of(MIB, 20),
                Arguments.of(16 * MIB, 24),
                Arguments.of(16 * MIB + 1, 25),
                Arguments.of(Integer.MAX_VALUE, 31));
    }

    private static Stream<Compressor> boundedCompressors() {
        return Stream.of(new GzipCompressor(), new ZstdCompressor(), new SnappyCompressor(), new NoOpCompressor());
    }

    private static Stream<Compressor> streamingCompressors() {
        return Stream.of(new GzipCompressor(), new ZstdCompressor());
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** Payloads that compress only partly, so a decompressed length isn't a multiple of anything convenient. */
    private static final class TestPayloadBytes {

        private TestPayloadBytes() {
        }

        static byte[] mixed(int length) {
            byte[] bytes = new byte[length];
            for (int i = 0; i < length; i++) {
                bytes[i] = (byte) ((i * 31 + i / 7) % 251);
            }
            return bytes;
        }
    }
}
