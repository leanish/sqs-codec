/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import com.google.errorprone.annotations.Immutable;

/**
 * Strategy interface for compressing and decompressing payload bytes.
 */
@Immutable
public interface Compressor {
    byte[] compress(byte[] payload);

    /**
     * Decompresses {@code payload}, producing at most {@code maxLength} bytes.
     *
     * @throws DecompressedPayloadTooLargeException when the payload would decompress to more than {@code maxLength} bytes
     * @throws CompressionException when the payload can't be decompressed
     * @throws IllegalArgumentException when {@code maxLength} is negative
     */
    byte[] decompress(byte[] payload, int maxLength);
}
