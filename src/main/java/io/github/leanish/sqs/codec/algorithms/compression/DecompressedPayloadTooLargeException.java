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

    // 0.6.0's computed value, pinned so adding factories doesn't change it.
    private static final long serialVersionUID = 5148972657466750515L;

    private DecompressedPayloadTooLargeException(String message) {
        super(message);
    }

    /**
     * Decoding passed {@code maxLength} bytes.
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
     * zstd's one-step retry reported its largest permitted output array too small. That's what zstd reported, not a
     * measured size: a malformed frame can report it too.
     *
     * @param algorithm the compression algorithm's id
     * @param ceiling the largest output array the retry may use
     * @return the exception to throw
     */
    static DecompressedPayloadTooLargeException oneStepOutputTooSmall(String algorithm, int ceiling) {
        return new DecompressedPayloadTooLargeException(
                algorithm + "'s one-step decoder reported its largest permitted output buffer (" + ceiling
                        + " bytes) too small");
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

    /**
     * A fresh payload to compress or encode has more UTF-16 code units than {@code maxLength}, so more UTF-8 bytes too
     * (each code unit takes at least one byte), refused before converting it to UTF-8.
     *
     * @param codeUnits the payload's length in UTF-16 code units ({@link String#length()})
     * @param maxLength the limit in bytes it's over
     * @return the exception to throw
     */
    public static DecompressedPayloadTooLargeException outboundCodeUnitsOverLimit(int codeUnits, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload has " + codeUnits + " UTF-16 code units, so more UTF-8 bytes than the " + maxLength
                        + "-byte maxDecodedPayloadBytes of a send configured to compress or encode");
    }
}
