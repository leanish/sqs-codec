/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Creates and validates checksum-related SQS message attributes.
 */
public class PayloadChecksumAttributeHandler {

    private final String checksumValue;

    private PayloadChecksumAttributeHandler(String checksumValue) {
        this.checksumValue = checksumValue;
    }

    public static PayloadChecksumAttributeHandler forOutbound(ChecksumAlgorithm checksumAlgorithm, byte[] payloadBytes) {
        String checksumValue = "";
        if (checksumAlgorithm != ChecksumAlgorithm.NONE) {
            checksumValue = checksumAlgorithm.digestor().checksum(payloadBytes);
        }
        return new PayloadChecksumAttributeHandler(checksumValue);
    }

    public static boolean hasAttributes(Map<String, MessageAttributeValue> attributes) {
        return attributes.containsKey(PayloadCodecAttributes.CHECKSUM);
    }

    public static boolean needsValidation(
            @Nullable String checksumValue,
            ChecksumAlgorithm checksumAlgorithm) {
        return StringUtils.isNotBlank(checksumValue) || checksumAlgorithm != ChecksumAlgorithm.NONE;
    }

    public static void validate(
            ChecksumAlgorithm checksumAlgorithm,
            @Nullable String checksumValue,
            byte[] payloadBytes) {
        if (checksumAlgorithm == ChecksumAlgorithm.NONE) {
            if (StringUtils.isNotBlank(checksumValue)) {
                throw ChecksumValidationException.missingAlgorithm();
            }
            return;
        }
        if (StringUtils.isBlank(checksumValue)) {
            throw ChecksumValidationException.missingAttribute(PayloadCodecAttributes.CHECKSUM);
        }

        String actualChecksum = checksumAlgorithm.digestor()
                .checksum(payloadBytes);
        if (!actualChecksum.equals(checksumValue)) {
            throw ChecksumValidationException.mismatch();
        }
    }

    public void applyTo(Map<String, MessageAttributeValue> attributes) {
        if (StringUtils.isNotBlank(checksumValue)) {
            attributes.put(PayloadCodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute(checksumValue));
        }
    }
}
