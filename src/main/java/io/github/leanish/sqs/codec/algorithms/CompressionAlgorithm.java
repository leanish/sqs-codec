/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.CodecException;
import io.github.leanish.sqs.codec.algorithms.compression.Compressor;
import io.github.leanish.sqs.codec.algorithms.compression.GzipCompressor;
import io.github.leanish.sqs.codec.algorithms.compression.NoOpCompressor;
import io.github.leanish.sqs.codec.algorithms.compression.SnappyCompressor;
import io.github.leanish.sqs.codec.algorithms.compression.ZstdCompressor;

/**
 * Supported compression algorithms and their compressor implementations.
 */
public enum CompressionAlgorithm {
    /** Zstandard compression for high ratio with good performance. */
    ZSTD("zstd", new ZstdCompressor()) {
        @Override
        public Compressor implementation(CompressionLevel compressionLevel) {
            return new ZstdCompressor(Objects.requireNonNull(compressionLevel, "compressionLevel"));
        }

        @Override
        public boolean supportsCompressionLevel() {
            return true;
        }
    },
    /** Snappy compression for low-latency payloads. */
    SNAPPY("snappy", new SnappyCompressor()),
    /** Gzip compression for interoperability with common tooling. */
    GZIP("gzip", new GzipCompressor()) {
        @Override
        public Compressor implementation(CompressionLevel compressionLevel) {
            return new GzipCompressor(Objects.requireNonNull(compressionLevel, "compressionLevel"));
        }

        @Override
        public boolean supportsCompressionLevel() {
            return true;
        }
    },
    /** No compression; payload bytes are left as-is. */
    NONE("none", new NoOpCompressor());

    private static final Map<String, CompressionAlgorithm> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    algorithm -> algorithm.id.toLowerCase(Locale.ROOT),
                    algorithm -> algorithm));

    private final String id;
    private final Compressor implementation;

    CompressionAlgorithm(String id, Compressor implementation) {
        this.id = id;
        this.implementation = implementation;
    }

    public String id() {
        return id;
    }

    /**
     * Returns the default compressor for this algorithm.
     */
    public Compressor implementation() {
        return implementation;
    }

    /**
     * Returns a compressor configured with the given non-null compression level.
     *
     * <p>Callers that accept an optional level should validate with
     * {@link #validateCompressionLevel(CompressionLevel)} before calling this overload.
     *
     * @param compressionLevel non-null compression level for algorithms that support it
     * @throws CodecException when this algorithm does not support explicit compression levels
     * @throws NullPointerException when {@code compressionLevel} is null
     */
    public Compressor implementation(CompressionLevel compressionLevel) {
        throw unsupportedCompressionLevel(this, Objects.requireNonNull(compressionLevel, "compressionLevel"));
    }

    /**
     * Validates an optional compression level for this algorithm.
     *
     * <p>{@code null} is always allowed and means "use the algorithm default".
     *
     * @param compressionLevel optional compression level to validate
     * @throws CodecException when a non-null level is not supported by this algorithm
     */
    public void validateCompressionLevel(@Nullable CompressionLevel compressionLevel) {
        if (compressionLevel == null || supportsCompressionLevel()) {
            return;
        }
        throw unsupportedCompressionLevel(this, compressionLevel);
    }

    /**
     * Returns whether this algorithm supports explicit compression-level selection.
     */
    public boolean supportsCompressionLevel() {
        return false;
    }

    public static CompressionAlgorithm fromId(String value) {
        if (value.isBlank()) {
            throw UnsupportedAlgorithmException.compression(value);
        }
        CompressionAlgorithm compression = BY_ID.get(value.toLowerCase(Locale.ROOT));
        if (compression == null) {
            throw UnsupportedAlgorithmException.compression(value);
        }
        return compression;
    }

    private static CodecException unsupportedCompressionLevel(
            CompressionAlgorithm compressionAlgorithm,
            CompressionLevel compressionLevel) {
        return new CodecException(
                "Compression level " + compressionLevel
                        + " is not supported for compression algorithm " + compressionAlgorithm.id);
    }
}
