/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

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
        for (CompressionAlgorithm compression : values()) {
            if (compression.id.equalsIgnoreCase(value)) {
                return compression;
            }
        }
        throw UnsupportedAlgorithmException.compression(value);
    }
}
