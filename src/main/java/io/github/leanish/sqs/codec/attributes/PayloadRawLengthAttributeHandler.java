/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Handles the raw payload length attribute for SQS messages.
 */
public class PayloadRawLengthAttributeHandler {

    @Nullable
    private final Integer rawLength;

    private PayloadRawLengthAttributeHandler(@Nullable Integer rawLength) {
        this.rawLength = rawLength;
    }

    public static PayloadRawLengthAttributeHandler forOutbound(int rawLength) {
        return new PayloadRawLengthAttributeHandler(rawLength);
    }

    public void applyTo(Map<String, MessageAttributeValue> attributes) {
        if (rawLength != null) {
            attributes.put(PayloadCodecAttributes.RAW_LENGTH, MessageAttributeUtils.numberAttribute(rawLength));
        }
    }
}
