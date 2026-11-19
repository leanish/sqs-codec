/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.IOException;

import org.xerial.snappy.Snappy;
import org.xerial.snappy.SnappyError;

import com.google.errorprone.annotations.Immutable;

/**
 * Snappy implementation of the compressor strategy.
 */
@Immutable
public class SnappyCompressor implements Compressor {

    private static final String ALGORITHM = "snappy";

    @Override
    public byte[] compress(byte[] payload) {
        try {
            return Snappy.compress(payload);
        } catch (IOException | RuntimeException | SnappyError e) {
            throw CompressionException.compress(ALGORITHM, e);
        }
    }

    @Override
    public byte[] decompress(byte[] payload, int maxLength) {
        BoundedReads.requireNonNegative(maxLength);
        try {
            // Snappy allocates the length a payload declares, so that length is checked before anything is allocated.
            // Snappy itself refuses a header it can't read, or a length implausible for the payload (a CompressionException
            // here); the negative check only guards against a declared length past Integer.MAX_VALUE getting through.
            int declaredLength = Snappy.uncompressedLength(payload);
            if (declaredLength < 0 || declaredLength > maxLength) {
                throw DecompressedPayloadTooLargeException.declaredOverLimit(
                        ALGORITHM, Integer.toUnsignedLong(declaredLength), maxLength);
            }
            return Snappy.uncompress(payload);
        } catch (DecompressedPayloadTooLargeException e) {
            throw e;
        } catch (IOException | RuntimeException | SnappyError e) {
            throw CompressionException.decompress(ALGORITHM, e);
        }
    }

    /**
     * Decompresses {@code payload}, producing at most 16 MiB (the interceptor's default limit).
     *
     * @param payload the compressed bytes
     * @return the decompressed bytes
     * @deprecated use {@link #decompress(byte[], int)}, which takes the limit
     */
    @Deprecated(since = "0.6.0")
    @Override
    // Declared again so code that looks the 0.5.x method up on this class (getDeclaredMethod) still finds it.
    public byte[] decompress(byte[] payload) {
        return Compressor.super.decompress(payload);
    }
}
