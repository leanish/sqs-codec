/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.CodecException;

/**
 * Thrown when checksum metadata is inconsistent or when checksum validation fails.
 * Examples: {@code h=none} with {@code s} present, or {@code h!=none} with missing/blank {@code s}.
 */
public class ChecksumValidationException extends CodecException {

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
                "Missing required codec metadata key: " + attributeName);
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
