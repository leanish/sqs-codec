/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import java.io.Serial;

import io.github.leanish.sqs.codec.CodecException;

/**
 * Thrown when payload compression or decompression fails.
 */
public class CompressionException extends CodecException {

    // 0.5.x's computed value: the members added in 0.6.0 would change it, and an instance serialized by 0.5.x would no
    // longer deserialize.
    @Serial
    private static final long serialVersionUID = -8112723582884947926L;

    protected CompressionException(String message) {
        super(message);
    }

    private CompressionException(String message, Throwable cause) {
        super(message, cause);
    }

    public static CompressionException compress(String algorithm, Throwable cause) {
        return new CompressionException(
                "Failed to compress payload with " + algorithm,
                cause);
    }

    public static CompressionException decompress(String algorithm, Throwable cause) {
        return new CompressionException(
                "Failed to decompress payload with " + algorithm,
                cause);
    }

    /**
     * The payload decoded to a length other than the raw length ({@code l}) its metadata declares.
     *
     * @param algorithm the compression algorithm's id
     * @param expectedLength the declared raw length
     * @param actualLength the decoded length
     * @return the exception to throw
     */
    public static CompressionException lengthMismatch(String algorithm, int expectedLength, int actualLength) {
        return new CompressionException(
                "Decompressed payload with " + algorithm + " has " + actualLength
                        + " bytes, but its metadata declares " + expectedLength);
    }
}
