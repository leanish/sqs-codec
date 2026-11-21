/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

/**
 * Thrown when a payload is refused against the limit it's decoded with (the interceptor's
 * {@code maxDecodedPayloadBytes}): decoding (decompressing, or decoding an encoding-only payload) passed it, zstd's
 * one-step retry couldn't fit the output in its largest permitted array, or a declared length is over it. It doesn't
 * establish the payload's actual decoded size. On a fresh send, it's also thrown, before compressing or encoding, for
 * a payload the interceptor is configured to compress or encode that's over the limit, since receivers with it would
 * refuse it once compressed or encoded.
 */
public class DecompressedPayloadTooLargeException extends CompressionException {

    private DecompressedPayloadTooLargeException(String message) {
        super(message);
    }

    /**
     * Decoding passed {@code maxLength} bytes, or zstd's one-step retry couldn't fit the output in {@code maxLength}
     * bytes (its largest permitted array).
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

    /**
     * A fresh payload to compress or encode has more than {@code maxLength} bytes, refused before sending it.
     *
     * @param payloadLength the payload's UTF-8 length in bytes
     * @param maxLength the limit it's over
     * @return the exception to throw
     */
    public static DecompressedPayloadTooLargeException outboundOverLimit(int payloadLength, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload has " + payloadLength + " bytes, over the " + maxLength
                        + "-byte maxDecodedPayloadBytes of a send configured to compress or encode");
    }
}
