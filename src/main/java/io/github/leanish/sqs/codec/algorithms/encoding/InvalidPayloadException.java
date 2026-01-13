/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms.encoding;

import io.github.leanish.sqs.codec.PayloadCodecException;

/**
 * Thrown when a payload cannot be decoded.
 */
public class InvalidPayloadException extends PayloadCodecException {

    public InvalidPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
