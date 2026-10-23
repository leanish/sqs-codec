/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.util.OptionalInt;

/**
 * Thrown when decompressing a payload would produce more bytes than the allowed limit.
 */
public class DecompressedPayloadTooLargeException extends CompressionException {

    private final boolean outputExceeded;
    private final OptionalInt declaredLength;

    private DecompressedPayloadTooLargeException(String message, boolean outputExceeded, OptionalInt declaredLength) {
        super(message);
        this.outputExceeded = outputExceeded;
        this.declaredLength = declaredLength;
    }

    /** Decompression produced more than {@code maxLength} bytes. */
    public static DecompressedPayloadTooLargeException exceeds(String algorithm, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Decompressed payload with " + algorithm + " exceeds " + maxLength + " bytes",
                true,
                OptionalInt.empty());
    }

    /** The payload declares a size the limit refuses, before anything was decompressed. */
    public static DecompressedPayloadTooLargeException declaredOverLimit(String algorithm, int declaredLength, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload with " + algorithm + " declares " + declaredLength + " decoded bytes, over the "
                        + maxLength + "-byte limit",
                false,
                OptionalInt.of(declaredLength));
    }

    /** The payload declares a decoding window the limit refuses, before anything was decompressed. */
    public static DecompressedPayloadTooLargeException windowOverLimit(String algorithm, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload with " + algorithm + " declares a decoding window larger than its " + maxLength
                        + "-byte limit allows",
                false,
                OptionalInt.empty());
    }

    /**
     * Whether decompression actually produced more bytes than the limit, rather than the payload declaring a size or
     * window the limit refuses up front.
     */
    public boolean outputExceeded() {
        return outputExceeded;
    }

    /**
     * The decoded length the payload itself declared, when that is what the limit refused (snappy's header, or a
     * declared raw length); empty for output that passed the limit and for a refused zstd window.
     */
    public OptionalInt declaredLength() {
        return declaredLength;
    }
}
