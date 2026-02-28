/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
    ZSTD("zstd", new ZstdCompressor()),
    /** Snappy compression for low-latency payloads. */
    SNAPPY("snappy", new SnappyCompressor()),
    /** Gzip compression for interoperability with common tooling. */
    GZIP("gzip", new GzipCompressor()),
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

    public Compressor implementation() {
        return implementation;
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
