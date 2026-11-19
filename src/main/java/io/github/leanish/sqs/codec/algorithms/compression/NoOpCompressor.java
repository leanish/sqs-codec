/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import com.google.errorprone.annotations.Immutable;

/**
 * No-op compressor that passes payload bytes through unchanged.
 */
@Immutable
public class NoOpCompressor implements Compressor {

    @Override
    public byte[] compress(byte[] payload) {
        return payload;
    }

    @Override
    public byte[] decompress(byte[] payload, int maxLength) {
        BoundedReads.requireNonNegative(maxLength);
        if (payload.length > maxLength) {
            throw DecompressedPayloadTooLargeException.exceeds("none", maxLength);
        }
        return payload;
    }

    /**
     * Returns {@code payload}, refusing one over 16 MiB (the interceptor's default limit).
     *
     * @param payload the payload bytes
     * @return the same bytes
     * @deprecated use {@link #decompress(byte[], int)}, which takes the limit
     */
    @Deprecated(since = "0.6.0")
    @Override
    // Declared again so code that looks the 0.5.x method up on this class (getDeclaredMethod) still finds it.
    public byte[] decompress(byte[] payload) {
        return Compressor.super.decompress(payload);
    }
}
