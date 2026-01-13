/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.PayloadCodecException;

/**
 * Thrown when checksum attributes are missing or fail validation.
 */
public class ChecksumValidationException extends PayloadCodecException {

    private final @Nullable String detail;

    private ChecksumValidationException(@Nullable String detail, String message) {
        super(message);
        this.detail = detail;
    }

    public static ChecksumValidationException missingAlgorithm() {
        return new ChecksumValidationException(
                null,
                "Missing required checksum algorithm");
    }

    public static ChecksumValidationException missingAttribute(String attributeName) {
        return new ChecksumValidationException(
                attributeName,
                "Missing required SQS attribute: " + attributeName);
    }

    public static ChecksumValidationException mismatch() {
        return new ChecksumValidationException(
                null,
                "Payload checksum mismatch");
    }

    public @Nullable String detail() {
        return detail;
    }
}
