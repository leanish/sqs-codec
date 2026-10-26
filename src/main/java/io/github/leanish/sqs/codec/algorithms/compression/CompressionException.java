/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.compression;

import io.github.leanish.sqs.codec.CodecException;

/**
 * Thrown when payload compression or decompression fails.
 */
public class CompressionException extends CodecException {

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

    public static CompressionException lengthMismatch(String algorithm, int expectedLength, int actualLength) {
        return new CompressionException(
                "Decompressed payload with " + algorithm + " has " + actualLength
                        + " bytes, but its metadata declares " + expectedLength);
    }
}
