/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import java.util.Map;

import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Writes the raw payload byte length attribute for SQS messages.
 *
 * <p>This attribute is for debugging and observability only. It is intentionally not validated on send/receive.
 */
public class PayloadRawLengthAttributeHandler {

    private final int rawLength;

    private PayloadRawLengthAttributeHandler(int rawLength) {
        this.rawLength = rawLength;
    }

    public static PayloadRawLengthAttributeHandler forOutbound(int rawLength) {
        return new PayloadRawLengthAttributeHandler(rawLength);
    }

    public void applyTo(Map<String, MessageAttributeValue> attributes) {
        attributes.put(CodecAttributes.RAW_LENGTH, MessageAttributeUtils.numberAttribute(rawLength));
    }
}
