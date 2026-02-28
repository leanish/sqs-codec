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
 * Helpers for reading and creating SQS message attributes.
 */
public class MessageAttributeUtils {

    private MessageAttributeUtils() {
    }

    @Nullable
    public static String attributeValue(Map<String, MessageAttributeValue> attributes, String name) {
        MessageAttributeValue attributeValue = attributes.get(name);
        if (attributeValue == null) {
            return null;
        }
        return attributeValue.stringValue();
    }

    public static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build();
    }
}
