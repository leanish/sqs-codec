/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

/**
 * Thrown when a payload is refused against the limit it's decoded with (the interceptor's
 * {@code maxDecodedPayloadBytes}): decoding produced more than it, or a declared length is over it. It doesn't
 * establish the payload's actual decoded size.
 */
public class DecompressedPayloadTooLargeException extends CompressionException {

    private DecompressedPayloadTooLargeException(String message) {
        super(message);
    }

    /**
     * Decompression produced more than {@code maxLength} bytes.
     *
     * @param algorithm the compression algorithm's id
     * @param maxLength the limit that was passed
     * @return the exception to throw
     */
    public static DecompressedPayloadTooLargeException exceeds(String algorithm, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Decompressed payload with " + algorithm + " exceeds " + maxLength + " bytes");
    }

    /**
     * The payload declares a decoded size over {@code maxLength}, refused before anything is decompressed.
     *
     * @param algorithm the compression algorithm's id
     * @param declaredLength the decoded size the payload declares
     * @param maxLength the limit it's over
     * @return the exception to throw
     */
    public static DecompressedPayloadTooLargeException declaredOverLimit(String algorithm, long declaredLength, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload with " + algorithm + " declares " + declaredLength + " decoded bytes, over the "
                        + maxLength + "-byte limit");
    }
}
