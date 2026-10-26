/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

/**
 * Thrown when a payload would decode to more bytes than its limit allows: the configured limit, or the raw length a
 * compressed payload declares.
 */
public class DecompressedPayloadTooLargeException extends CompressionException {

    private DecompressedPayloadTooLargeException(String message) {
        super(message);
    }

    /** Decompression produced more than {@code maxLength} bytes. */
    public static DecompressedPayloadTooLargeException exceeds(String algorithm, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Decompressed payload with " + algorithm + " exceeds " + maxLength + " bytes");
    }

    /** The payload declares a decoded size over {@code maxLength}, refused before anything is decompressed. */
    public static DecompressedPayloadTooLargeException declaredOverLimit(String algorithm, long declaredLength, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload with " + algorithm + " declares " + declaredLength + " decoded bytes, over the "
                        + maxLength + "-byte limit");
    }

    /**
     * The payload declares a decoding window over what {@code maxLength} needs, refused before anything is decompressed:
     * its output may be small, but the decoder would allocate the window.
     */
    public static DecompressedPayloadTooLargeException windowOverLimit(String algorithm, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload with " + algorithm + " declares a decoding window larger than its " + maxLength
                        + "-byte limit allows");
    }
}
