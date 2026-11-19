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
     * @param payload the compressed bytes
     * @param maxLength the most bytes the payload may decompress to
     * @return the decompressed bytes
     * @throws DecompressedPayloadTooLargeException when the payload would decompress to more than {@code maxLength} bytes
     * @throws CompressionException when the payload can't be decompressed
     * @throws IllegalArgumentException when {@code maxLength} is negative
     */
    byte[] decompress(byte[] payload, int maxLength);

    /**
     * Decompresses {@code payload}, producing at most 16 MiB, the interceptor's default limit:
     * {@code decompress(payload, 16 MiB)}. Kept so code built against 0.5.x keeps working; 0.5.x didn't bound it.
     *
     * @param payload the compressed bytes
     * @return the decompressed bytes
     * @throws DecompressedPayloadTooLargeException when the payload would decompress to more than 16 MiB, or, for
     *         zstd, would need a larger window than 16 MiB needs (as 0.5.x's streamed frames at the {@code MAXIMUM}
     *         level do; a frame zstd decodes in a single step allocates no window)
     * @throws CompressionException when the payload can't be decompressed
     * @deprecated use {@link #decompress(byte[], int)}, which takes the limit
     */
    @Deprecated(since = "0.6.0")
    default byte[] decompress(byte[] payload) {
        // SqsCodecInterceptor.DEFAULT_MAX_DECODED_PAYLOAD_BYTES, written out so this package doesn't depend on the root one.
        return decompress(payload, 16 * 1024 * 1024);
    }
}
