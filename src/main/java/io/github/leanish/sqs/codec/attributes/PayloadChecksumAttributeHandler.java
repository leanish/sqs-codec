/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Creates and validates checksum-related SQS message attributes.
 */
public class PayloadChecksumAttributeHandler {

    @Nullable
    private final String checksumValue;

    private PayloadChecksumAttributeHandler(@Nullable String checksumValue) {
        this.checksumValue = checksumValue;
    }

    public static PayloadChecksumAttributeHandler forOutbound(ChecksumAlgorithm checksumAlgorithm, byte[] payloadBytes) {
        if (checksumAlgorithm == ChecksumAlgorithm.NONE) {
            return new PayloadChecksumAttributeHandler(null);
        }
        return new PayloadChecksumAttributeHandler(checksumAlgorithm.implementation().checksum(payloadBytes));
    }

    public static boolean needsValidation(
            boolean checksumAttributePresent,
            ChecksumAlgorithm checksumAlgorithm) {
        return checksumAttributePresent || checksumAlgorithm != ChecksumAlgorithm.NONE;
    }

    public static void validate(
            ChecksumAlgorithm checksumAlgorithm,
            boolean checksumAttributePresent,
            @Nullable String checksumValue,
            byte[] payloadBytes) {
        if (checksumAlgorithm == ChecksumAlgorithm.NONE) {
            if (checksumAttributePresent) {
                throw ChecksumValidationException.missingAlgorithm();
            }
            return;
        }
        if (!checksumAttributePresent || checksumValue == null || checksumValue.isBlank()) {
            throw ChecksumValidationException.missingAttribute(CodecAttributes.CHECKSUM);
        }

        String actualChecksum = checksumAlgorithm.implementation()
                .checksum(payloadBytes);
        if (!actualChecksum.equals(checksumValue)) {
            throw ChecksumValidationException.mismatch();
        }
    }

    public void applyTo(Map<String, MessageAttributeValue> attributes) {
        if (checksumValue == null) {
            return;
        }
        attributes.put(CodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute(checksumValue));
    }
}
