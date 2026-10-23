/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

/**
 * Thrown when decompressing a payload would produce more bytes than the allowed limit.
 */
public class DecompressedPayloadTooLargeException extends CompressionException {

    private final boolean outputExceeded;

    private DecompressedPayloadTooLargeException(String message, boolean outputExceeded) {
        super(message);
        this.outputExceeded = outputExceeded;
    }

    /** Decompression produced more than {@code maxLength} bytes. */
    public static DecompressedPayloadTooLargeException exceeds(String algorithm, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Decompressed payload with " + algorithm + " exceeds " + maxLength + " bytes",
                true);
    }

    /** The payload declares a size the limit refuses, before anything was decompressed. */
    public static DecompressedPayloadTooLargeException declaredOverLimit(String algorithm, int declaredLength, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload with " + algorithm + " declares " + declaredLength + " decoded bytes, over the "
                        + maxLength + "-byte limit",
                false);
    }

    /** The payload declares a decoding window the limit refuses, before anything was decompressed. */
    public static DecompressedPayloadTooLargeException windowOverLimit(String algorithm, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload with " + algorithm + " declares a decoding window larger than its " + maxLength
                        + "-byte limit allows",
                false);
    }

    /**
     * Whether decompression actually produced more bytes than the limit, rather than the payload declaring a size or
     * window the limit refuses up front.
     */
    public boolean outputExceeded() {
        return outputExceeded;
    }
}
