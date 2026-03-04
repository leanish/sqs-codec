/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import io.github.leanish.sqs.codec.CodecException;

/**
 * Thrown when an unsupported algorithm id is encountered.
 */
public class UnsupportedAlgorithmException extends CodecException {

    public UnsupportedAlgorithmException(String message) {
        super(message);
    }

    public static UnsupportedAlgorithmException compression(String value) {
        return new UnsupportedAlgorithmException("Unsupported payload compression: " + value);
    }

    public static UnsupportedAlgorithmException checksum(String value) {
        return new UnsupportedAlgorithmException("Unsupported checksum algorithm: " + value);
    }
}
