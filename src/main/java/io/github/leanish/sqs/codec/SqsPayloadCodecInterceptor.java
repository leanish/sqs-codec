/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.commons.lang3.StringUtils;

import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

public class SqsPayloadCodecInterceptor implements ExecutionInterceptor {

    private final PayloadCodec payloadCodec;
    private final PayloadEncoding outboundEncoding;

    public SqsPayloadCodecInterceptor() {
        this(PayloadCodecs.forEncoding(PayloadEncoding.ZSTD_BASE64));
    }

    public SqsPayloadCodecInterceptor(PayloadEncoding outboundEncoding) {
        this(PayloadCodecs.forEncoding(Objects.requireNonNull(outboundEncoding, "outboundEncoding")));
    }

    public SqsPayloadCodecInterceptor(PayloadCodec payloadCodec) {
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.outboundEncoding = payloadCodec.encoding();
    }

    @Override
    public SdkRequest modifyRequest(Context.ModifyRequest context, ExecutionAttributes executionAttributes) {
        SdkRequest request = context.request();
        if (request instanceof SendMessageRequest sendMessageRequest) {
            return encodeSendMessage(sendMessageRequest);
        }
        if (request instanceof SendMessageBatchRequest sendMessageBatchRequest) {
            return encodeSendMessageBatch(sendMessageBatchRequest);
        }
        return request;
    }

    @Override
    public SdkResponse modifyResponse(Context.ModifyResponse context, ExecutionAttributes executionAttributes) {
        SdkResponse response = context.response();
        if (response instanceof ReceiveMessageResponse receiveMessageResponse) {
            return decodeReceiveMessageResponse(receiveMessageResponse);
        }
        return response;
    }

    private SendMessageRequest encodeSendMessage(SendMessageRequest request) {
        if (shouldSkipEncoding(request.messageAttributes())) {
            return request;
        }
        byte[] payloadBytes = request.messageBody().getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>(request.messageAttributes());
        attributes.put(PayloadCodecAttributes.ENCODING, payloadEncodingAttribute());
        attributes.put(PayloadCodecAttributes.CHECKSUM_ALG, checksumAlgorithmAttribute());
        attributes.put(PayloadCodecAttributes.CHECKSUM, checksumAttribute(payloadBytes));
        attributes.put(PayloadCodecAttributes.VERSION, versionAttribute());
        attributes.put(PayloadCodecAttributes.RAW_LENGTH, rawLengthAttribute(payloadBytes.length));
        String encodedBody = payloadCodec.encode(payloadBytes);
        return request.toBuilder()
                .messageBody(encodedBody)
                .messageAttributes(attributes)
                .build();
    }

    private SendMessageBatchRequest encodeSendMessageBatch(SendMessageBatchRequest request) {
        List<SendMessageBatchRequestEntry> entries = request.entries();
        List<SendMessageBatchRequestEntry> encodedEntries = entries.stream()
                .map(this::encodeSendMessageEntry)
                .toList();
        return request.toBuilder()
                .entries(encodedEntries)
                .build();
    }

    private SendMessageBatchRequestEntry encodeSendMessageEntry(SendMessageBatchRequestEntry entry) {
        if (shouldSkipEncoding(entry.messageAttributes())) {
            return entry;
        }
        byte[] payloadBytes = entry.messageBody().getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>(entry.messageAttributes());
        attributes.put(PayloadCodecAttributes.ENCODING, payloadEncodingAttribute());
        attributes.put(PayloadCodecAttributes.CHECKSUM_ALG, checksumAlgorithmAttribute());
        attributes.put(PayloadCodecAttributes.CHECKSUM, checksumAttribute(payloadBytes));
        attributes.put(PayloadCodecAttributes.VERSION, versionAttribute());
        attributes.put(PayloadCodecAttributes.RAW_LENGTH, rawLengthAttribute(payloadBytes.length));
        String encodedBody = payloadCodec.encode(payloadBytes);
        return entry.toBuilder()
                .messageBody(encodedBody)
                .messageAttributes(attributes)
                .build();
    }

    private boolean shouldSkipEncoding(Map<String, MessageAttributeValue> messageAttributes) {
        MessageAttributeValue attributeValue = messageAttributes.get(PayloadCodecAttributes.ENCODING);
        if (attributeValue == null) {
            return false;
        }
        String value = attributeValue.stringValue();
        return StringUtils.isNotBlank(value);
    }

    private MessageAttributeValue payloadEncodingAttribute() {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(outboundEncoding.attributeValue())
                .build();
    }

    private MessageAttributeValue checksumAlgorithmAttribute() {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(PayloadCodecAttributes.CHECKSUM_SHA256)
                .build();
    }

    private MessageAttributeValue checksumAttribute(byte[] payloadBytes) {
        String checksum = PayloadChecksum.sha256Base64(payloadBytes);
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(checksum)
                .build();
    }

    private MessageAttributeValue versionAttribute() {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(PayloadCodecAttributes.VERSION_VALUE)
                .build();
    }

    private MessageAttributeValue rawLengthAttribute(int length) {
        return MessageAttributeValue.builder()
                .dataType("Number")
                .stringValue(Integer.toString(length))
                .build();
    }

    private ReceiveMessageResponse decodeReceiveMessageResponse(ReceiveMessageResponse response) {
        List<Message> messages = response.messages();
        if (messages.isEmpty()) {
            return response;
        }
        List<Message> decoded = new ArrayList<>(messages.size());
        for (Message message : messages) {
            decoded.add(decodeMessageIfNeeded(message));
        }
        return response.toBuilder()
                .messages(decoded)
                .build();
    }

    private Message decodeMessageIfNeeded(Message message) {
        Map<String, MessageAttributeValue> attributes = message.messageAttributes();
        MessageAttributeValue encodingAttribute = attributes.get(PayloadCodecAttributes.ENCODING);
        if (encodingAttribute == null) {
            return message;
        }
        String encodingValue = encodingAttribute.stringValue();
        if (StringUtils.isBlank(encodingValue)) {
            return message;
        }
        PayloadEncoding encoding = PayloadEncoding.fromAttributeValue(encodingValue);
        String version = requiredAttribute(attributes, PayloadCodecAttributes.VERSION);
        if (!PayloadCodecAttributes.VERSION_VALUE.equals(version)) {
            throw new PayloadCodecException("Unsupported codec version: " + version);
        }
        String checksumAlgorithm = requiredAttribute(attributes, PayloadCodecAttributes.CHECKSUM_ALG);
        if (!PayloadCodecAttributes.CHECKSUM_SHA256.equals(checksumAlgorithm)) {
            throw new PayloadCodecException("Unsupported checksum algorithm: " + checksumAlgorithm);
        }
        String expectedChecksum = requiredAttribute(attributes, PayloadCodecAttributes.CHECKSUM);

        PayloadCodec codec = PayloadCodecs.forEncoding(encoding);
        byte[] decodedBytes = codec.decodeToBytes(message.body());
        String actualChecksum = PayloadChecksum.sha256Base64(decodedBytes);
        if (!actualChecksum.equals(expectedChecksum)) {
            throw new PayloadCodecException("Payload checksum mismatch");
        }
        String decodedBody = new String(decodedBytes, StandardCharsets.UTF_8);
        return message.toBuilder()
                .body(decodedBody)
                .build();
    }

    private static String requiredAttribute(Map<String, MessageAttributeValue> attributes, String name) {
        MessageAttributeValue attributeValue = attributes.get(name);
        if (attributeValue == null) {
            throw new PayloadCodecException("Missing required SQS attribute: " + name);
        }
        String value = attributeValue.stringValue();
        if (StringUtils.isBlank(value)) {
            throw new PayloadCodecException("Missing required SQS attribute: " + name);
        }
        return value;
    }
}
