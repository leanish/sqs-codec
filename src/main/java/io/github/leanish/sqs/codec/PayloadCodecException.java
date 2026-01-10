/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

public final class PayloadCodecException extends RuntimeException {

    public PayloadCodecException(String message) {
        super(message);
    }

    public PayloadCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
