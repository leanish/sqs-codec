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

import com.google.errorprone.annotations.Immutable;

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
    ZSTD("zstd", new ZstdCompressor(), ZstdCompressor::new),
    /** Snappy compression for low-latency payloads. */
    SNAPPY("snappy", new SnappyCompressor(), null),
    /** Gzip compression for interoperability with common tooling. */
    GZIP("gzip", new GzipCompressor(), GzipCompressor::new),
    /** No compression; payload bytes are left as-is. */
    NONE("none", new NoOpCompressor(), null);

    private static final Map<String, CompressionAlgorithm> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    algorithm -> algorithm.id.toLowerCase(Locale.ROOT),
                    algorithm -> algorithm));

    private final String id;
    private final Compressor baseCompressor;
    private final @Nullable LeveledCompressorFactory leveledFactory;

    CompressionAlgorithm(
            String id,
            Compressor baseCompressor,
            @Nullable LeveledCompressorFactory leveledFactory) {
        this.id = id;
        this.baseCompressor = baseCompressor;
        this.leveledFactory = leveledFactory;
    }

    @Immutable
    @FunctionalInterface
    private interface LeveledCompressorFactory {
        Compressor create(CompressionLevel compressionLevel);
    }

    public String id() {
        return id;
    }

    /**
     * Returns the default compressor for this algorithm.
     */
    public Compressor implementation() {
        return baseCompressor;
    }

    /**
     * Returns a compressor configured with the given non-null compression level.
     *
     * <p>Callers that accept an optional level should validate with
     * {@link #validateCompressionLevel(CompressionLevel)} before calling this overload.
     *
     * @param compressionLevel non-null compression level for algorithms that support it
     * @throws UnsupportedAlgorithmException when this algorithm does not support explicit compression levels
     * @throws NullPointerException when {@code compressionLevel} is null
     */
    public Compressor implementation(CompressionLevel compressionLevel) {
        Objects.requireNonNull(compressionLevel, "compressionLevel");
        if (leveledFactory == null) {
            throw UnsupportedAlgorithmException.compressionLevel(this, compressionLevel);
        }
        return leveledFactory.create(compressionLevel);
    }

    /**
     * Validates an optional compression level for this algorithm.
     *
     * <p>{@code null} is always allowed and means "use the algorithm default".
     *
     * @param compressionLevel optional compression level to validate
     * @throws UnsupportedAlgorithmException when a non-null level is not supported by this algorithm
     */
    public void validateCompressionLevel(@Nullable CompressionLevel compressionLevel) {
        if (compressionLevel != null && leveledFactory == null) {
            throw UnsupportedAlgorithmException.compressionLevel(this, compressionLevel);
        }
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
}
