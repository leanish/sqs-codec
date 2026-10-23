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

    private DecompressedPayloadTooLargeException(String message) {
        super(message);
    }

    public static DecompressedPayloadTooLargeException exceeds(String algorithm, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Decompressed payload with " + algorithm + " exceeds " + maxLength + " bytes");
    }

    public static DecompressedPayloadTooLargeException declaredOverLimit(String algorithm, int declaredLength, int maxLength) {
        return new DecompressedPayloadTooLargeException(
                "Payload with " + algorithm + " declares " + declaredLength + " decoded bytes, over the "
                        + maxLength + "-byte limit");
    }
}
