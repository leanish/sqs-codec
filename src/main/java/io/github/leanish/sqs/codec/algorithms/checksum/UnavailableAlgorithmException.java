/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.checksum;

import io.github.leanish.sqs.codec.PayloadCodecException;

/**
 * Thrown when a digest algorithm is unavailable at runtime.
 */
public class UnavailableAlgorithmException extends PayloadCodecException {

    public UnavailableAlgorithmException(String message) {
        super(message);
    }

    public UnavailableAlgorithmException(String message, Throwable cause) {
        super(message, cause);
    }
}
