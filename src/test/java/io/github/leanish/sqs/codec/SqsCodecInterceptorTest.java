/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;

import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import io.github.leanish.sqs.codec.algorithms.UnsupportedAlgorithmException;
import io.github.leanish.sqs.codec.algorithms.encoding.InvalidPayloadException;
import io.github.leanish.sqs.codec.attributes.ChecksumValidationException;
import io.github.leanish.sqs.codec.attributes.CodecAttributes;
import io.github.leanish.sqs.codec.attributes.CodecMetadataAttributeHandler;
import io.github.leanish.sqs.codec.attributes.MessageAttributeUtils;
import io.github.leanish.sqs.codec.attributes.UnsupportedCodecMetadataException;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class SqsCodecInterceptorTest {

    private static final String PAYLOAD = "{\"value\":42}";

    @Test
    void modifyRequest_happyCase() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withPreferSmallerPayloadEnabled(false);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of("shopId", MessageAttributeUtils.stringAttribute("shop-1")))
                .build();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(SendMessageRequest.class);
        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageAttributes())
                .containsKeys(
                        CodecAttributes.META,
                        "shopId");
        String expectedChecksum = ChecksumAlgorithm.MD5.implementation().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=zstd;e=base64;h=md5;s=" + expectedChecksum + ";l=12");
        assertThat(encoded.messageBody())
                .isNotEqualTo(PAYLOAD);
        Codec codec = new Codec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
        assertThat(new String(codec.decode(encoded.messageBody().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyRequest_prefersOriginalPayloadWhenCompressedPayloadIsLarger() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(encoded.messageBody())
                .isEqualTo(PAYLOAD);
        String expectedChecksum = ChecksumAlgorithm.MD5.implementation().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=none;e=none;h=md5;s=" + expectedChecksum + ";l=12");
        assertThat(encoded.messageAttributes())
                .containsOnlyKeys(CodecAttributes.META);
    }

    @Test
    void modifyRequest_alwaysIncludesRawLengthMetadata() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(encoded.messageAttributes())
                .containsKey(CodecAttributes.META);
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .contains(";l=" + PAYLOAD.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void modifyRequest_alreadyPresentAttributes() {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute(
                                "v=1;c=none;e=none;h=md5;s="
                                        + ChecksumAlgorithm.MD5.implementation().checksum(payloadBytes)
                                        + ";l=12")))
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor();

        SdkRequest modified = interceptor.modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        assertThat(modified)
                .isSameAs(request);
    }

    @Test
    void modifyRequest_alreadyPresentAttributes_missingChecksum() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=md5;l=12")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOfSatisfying(ChecksumValidationException.class, exception -> {
                    assertThat(exception.detail()).isEqualTo(CodecAttributes.META_CHECKSUM_VALUE_KEY);
                })
                .hasMessage("Missing required codec metadata key: " + CodecAttributes.META_CHECKSUM_VALUE_KEY);
    }

    @Test
    void modifyRequest_alreadyPresentAttributes_checksumMismatch() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=md5;s=bad;l=12")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(ChecksumValidationException.class)
                .hasMessage("Payload checksum mismatch");
    }

    @Test
    void modifyRequest_alreadyPresentAttributes_checksumPresentYetNoAlgorithm() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=none;s=bad;l=12")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(ChecksumValidationException.class)
                .hasMessage("Missing required checksum algorithm");
    }

    @Test
    void modifyRequest_alreadyPresentAttributes_blankChecksumYetNoAlgorithm() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=none;s=;l=12")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(ChecksumValidationException.class)
                .hasMessage("Missing required checksum algorithm");
    }

    @Test
    void modifyRequest_alreadyPresentAttributes_invalidEncodedBody() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=1;c=zstd;e=base64;h=none;l=12")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload");
    }

    @Test
    void modifyRequest_alreadyPresentAttributes_rawLengthMismatchIgnored() {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute(
                                "v=1;c=none;e=none;h=md5;s="
                                        + ChecksumAlgorithm.MD5.implementation().checksum(payloadBytes)
                                        + ";l="
                                        + (payloadBytes.length + 1))))
                .build();

        SendMessageRequest encoded = (SendMessageRequest) SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        assertThat(encoded).isSameAs(request);
    }

    @Test
    void modifyRequest_invalidConfiguration() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=2;c=none;e=none;h=md5")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Unsupported codec version: 2");
    }

    @Test
    void modifyRequest_blankConfigurationAttribute() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute(" ")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Unsupported codec metadata:  ");
    }

    @Test
    void modifyRequest_explicitEncoding() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64_STD)
                .withChecksumAlgorithm(ChecksumAlgorithm.MD5)
                .withPreferSmallerPayloadEnabled(false);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SdkRequest modified = interceptor.modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .startsWith("v=1;c=zstd;e=base64-std;h=md5;s=");
    }

    @Test
    void modifyRequest_unknownRequests() {
        SdkRequest request = Mockito.mock(SdkRequest.class);

        SdkRequest modified = SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        assertThat(modified)
                .isSameAs(request);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("receiveMessageAttributeCases")
    void modifyRequest_receiveMessageAttributes(
            String scenario,
            List<String> attributeNames,
            boolean expectSameInstance,
            List<String> expectedAttributeNames) {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl("queue")
                .messageAttributeNames(attributeNames)
                .build();

        SdkRequest modified = SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        if (expectSameInstance) {
            assertThat(modified).isSameAs(request);
        }
        assertThat(modified).isInstanceOf(ReceiveMessageRequest.class);
        ReceiveMessageRequest updated = (ReceiveMessageRequest) modified;
        assertThat(updated.messageAttributeNames())
                .containsExactlyInAnyOrderElementsOf(expectedAttributeNames);
    }

    @Test
    void modifyRequest_noChecksum() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        assertThat(encoded.messageBody()).isEqualTo(PAYLOAD);
        assertThat(encoded.messageAttributes())
                .containsOnlyKeys(CodecAttributes.META);
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=none;e=none;h=none;l=12");
    }

    @Test
    void modifyRequest_attributeLimitNotExceededAtTenTotal() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(customAttributes(9))
                .build();

        SdkRequest modified = SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        assertThat(modified).isInstanceOf(SendMessageRequest.class);
    }

    @Test
    void modifyRequest_attributeLimitExceeded() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(customAttributes(10))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(CodecException.class)
                .hasMessageContaining("SQS supports at most 10 message attributes")
                .hasMessageContaining("request has 11")
                .hasMessageContaining("reduce custom attributes");
    }

    @Test
    void modifyRequest_batch() {
        String skippedPayload = "skip";
        byte[] skippedPayloadBytes = skippedPayload.getBytes(StandardCharsets.UTF_8);
        Codec skippedCodec = new Codec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
        String skippedBody = new String(skippedCodec.encode(skippedPayloadBytes), StandardCharsets.UTF_8);

        SendMessageBatchRequest request = SendMessageBatchRequest.builder()
                .entries(
                        SendMessageBatchRequestEntry.builder()
                                .id("encoded")
                                .messageBody(PAYLOAD)
                                .messageAttributes(Map.of("shopId", MessageAttributeUtils.stringAttribute("shop-1")))
                                .build(),
                        SendMessageBatchRequestEntry.builder()
                                .id("skipped")
                                .messageBody(skippedBody)
                                .messageAttributes(Map.of(
                                        CodecAttributes.META,
                                        MessageAttributeUtils.stringAttribute(
                                                "v=1;c=zstd;e=base64;h=md5;s="
                                                        + ChecksumAlgorithm.MD5.implementation().checksum(skippedPayloadBytes)
                                                        + ";l=4")))
                                .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.NONE)
                .withPreferSmallerPayloadEnabled(false);

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        SendMessageBatchRequest encoded = (SendMessageBatchRequest) modified;
        SendMessageBatchRequestEntry encodedEntry = encoded.entries().stream()
                .filter(entry -> "encoded".equals(entry.id()))
                .findFirst()
                .orElseThrow();
        SendMessageBatchRequestEntry skippedEntry = encoded.entries().stream()
                .filter(entry -> "skipped".equals(entry.id()))
                .findFirst()
                .orElseThrow();

        assertThat(encodedEntry.messageAttributes())
                .containsKeys(CodecAttributes.META);
        assertThat(encodedEntry.messageBody()).isNotEqualTo(PAYLOAD);
        Codec codec = new Codec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
        assertThat(new String(codec.decode(encodedEntry.messageBody().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(PAYLOAD);
        assertThat(skippedEntry)
                .isSameAs(request.entries().get(1));
    }

    @ParameterizedTest(name = "message={0}/{1}, interceptor={2}/{3}")
    @MethodSource("codecConfigurationPairs")
    void modifyResponse_codecConfigurationPairs(
            CompressionAlgorithm messageCompression,
            EncodingAlgorithm messageEncoding,
            CompressionAlgorithm interceptorCompression,
            EncodingAlgorithm interceptorEncoding) {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Codec codec = new Codec(messageCompression, messageEncoding);
        String encodedBody = new String(codec.encode(payloadBytes), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payloadBytes,
                messageCompression,
                messageEncoding,
                ChecksumAlgorithm.NONE);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(interceptorCompression)
                .withEncodingAlgorithm(interceptorEncoding)
                .withChecksumAlgorithm(ChecksumAlgorithm.SHA256);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages())
                .hasSize(1);
        assertThat(decoded.messages().getFirst().body())
                .isEqualTo(PAYLOAD);
    }

    @ParameterizedTest(name = "message={0}, interceptor={1}")
    @MethodSource("checksumConfigurationPairs")
    void modifyResponse_checksumConfigurationPairs(
            ChecksumAlgorithm messageChecksum,
            ChecksumAlgorithm interceptorChecksum) {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Codec codec = new Codec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(payloadBytes), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payloadBytes,
                CompressionAlgorithm.ZSTD,
                EncodingAlgorithm.NONE,
                messageChecksum);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64_STD)
                .withChecksumAlgorithm(interceptorChecksum);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages())
                .hasSize(1);
        assertThat(decoded.messages().getFirst().body())
                .isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("checksumAlgorithms")
    void modifyResponse_missingCompressionAndEncodingAttributes(ChecksumAlgorithm checksumAlgorithm) {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(
                CodecAttributes.META,
                MessageAttributeUtils.stringAttribute(
                        "v=1;h=" + checksumAlgorithm.id()
                                + ";l=12;s="
                                + checksumAlgorithm.implementation().checksum(payloadBytes)));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withChecksumAlgorithm(otherChecksumAlgorithm(checksumAlgorithm));

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_multipleMessages() {
        Codec codec = new Codec(CompressionAlgorithm.NONE, EncodingAlgorithm.BASE64);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Message encodedMessage = Message.builder()
                .body(encodedBody)
                .messageAttributes(codecAttributes(
                        PAYLOAD.getBytes(StandardCharsets.UTF_8),
                        CompressionAlgorithm.NONE,
                        EncodingAlgorithm.BASE64,
                        ChecksumAlgorithm.MD5))
                .build();
        Message plainMessage = Message.builder()
                .body("plain")
                .build();
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(encodedMessage, plainMessage)
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages())
                .hasSize(2);
        assertThat(decoded.messages().get(0).body())
                .isEqualTo(PAYLOAD);
        assertThat(decoded.messages().get(1))
                .isSameAs(plainMessage);
    }

    @Test
    void modifyResponse_noCodecAttributes() {
        Message message = Message.builder()
                .body(PAYLOAD)
                .build();
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(message)
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().getFirst())
                .isSameAs(message);
        assertThat(decoded.messages().getFirst().body())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_noMessages() {
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(List.of())
                .build();

        SdkResponse modified = SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(modified)
                .isSameAs(response);
    }

    @Test
    void modifyResponse_emptyResponses() {
        SdkResponse response = Mockito.mock(SdkResponse.class);

        SdkResponse modified = SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(modified)
                .isSameAs(response);
    }

    @Test
    void modifyResponse_disabledChecksum() {
        Codec codec = new Codec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.BASE64);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.ZSTD,
                EncodingAlgorithm.BASE64,
                ChecksumAlgorithm.NONE);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64)
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_noneAlgorithmYetPresentChecksum() {
        Codec codec = new Codec(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        attributes.put(CodecAttributes.META,
                MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=none;s=bad;l=12"));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(ChecksumValidationException.class)
                .hasMessage("Missing required checksum algorithm");
    }

    @Test
    void modifyResponse_caseInsensitiveAlgorithm() {
        Codec codec = new Codec(CompressionAlgorithm.GZIP, EncodingAlgorithm.BASE64_STD);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(CodecAttributes.META,
                MessageAttributeUtils.stringAttribute(
                        "V=1;C=GZIP;E=BASE64-STD;H=MD5;L=12;S="
                                + ChecksumAlgorithm.MD5.implementation().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8))));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("defaultedAttributeCases")
    void modifyResponse_missingCodecAttributes(
            Map<String, MessageAttributeValue> attributes,
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm) {
        Codec codec = new Codec(compressionAlgorithm, encodingAlgorithm);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("unsupportedAttributeCases")
    void modifyResponse_unsupportedAttributes(
            Map<String, MessageAttributeValue> attributes,
            Class<? extends CodecException> expectedException,
            String expectedMessage) {
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(expectedException)
                .hasMessage(expectedMessage);
    }

    @Test
    void modifyResponse_blankConfigurationAttribute() {
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(Map.of(
                                CodecAttributes.META,
                                MessageAttributeUtils.stringAttribute(" ")))
                        .build())
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Unsupported codec metadata:  ");
    }

    @Test
    void modifyResponse_missingChecksum() {
        Codec codec = new Codec(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        String metadataWithoutChecksum = attributes.get(CodecAttributes.META).stringValue()
                .replaceFirst(";s=[^;]*", "");
        attributes.put(CodecAttributes.META, MessageAttributeUtils.stringAttribute(metadataWithoutChecksum));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOfSatisfying(ChecksumValidationException.class, exception -> {
                    assertThat(exception.detail()).isEqualTo(CodecAttributes.META_CHECKSUM_VALUE_KEY);
                })
                .hasMessage("Missing required codec metadata key: " + CodecAttributes.META_CHECKSUM_VALUE_KEY);
    }

    @Test
    void modifyResponse_blankChecksum() {
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        String metadataWithBlankChecksum = attributes.get(CodecAttributes.META).stringValue()
                .replaceFirst(";s=[^;]*", ";s=");
        attributes.put(CodecAttributes.META, MessageAttributeUtils.stringAttribute(metadataWithBlankChecksum));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOfSatisfying(ChecksumValidationException.class, exception -> {
                    assertThat(exception.detail()).isEqualTo(CodecAttributes.META_CHECKSUM_VALUE_KEY);
                })
                .hasMessage("Missing required codec metadata key: " + CodecAttributes.META_CHECKSUM_VALUE_KEY);
    }

    @Test
    void modifyResponse_checksumMismatch() {
        Codec codec = new Codec(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        String metadataWithBadChecksum = attributes.get(CodecAttributes.META).stringValue()
                .replaceFirst(";s=[^;]*", ";s=bad");
        attributes.put(CodecAttributes.META, MessageAttributeUtils.stringAttribute(metadataWithBadChecksum));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(ChecksumValidationException.class)
                .hasMessage("Payload checksum mismatch");
    }

    @Test
    void modifyResponse_invalidBase64Payload() {
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.BASE64,
                ChecksumAlgorithm.MD5);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body("!!")
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload");
    }

    private static Stream<Arguments> defaultedAttributeCases() {
        Map<String, MessageAttributeValue> emptyAttributes = Map.of();
        Map<String, MessageAttributeValue> missingCompression = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;e=base64;l=12"));
        Map<String, MessageAttributeValue> missingEncoding = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=zstd;l=12"));
        Map<String, MessageAttributeValue> missingVersion = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("c=none;e=none;h=none;l=12"));
        Map<String, MessageAttributeValue> missingChecksum = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=gzip;e=base64;l=12"));

        return Stream.of(
                Arguments.of(emptyAttributes, CompressionAlgorithm.NONE, EncodingAlgorithm.NONE),
                Arguments.of(missingCompression, CompressionAlgorithm.NONE, EncodingAlgorithm.BASE64),
                Arguments.of(missingEncoding, CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE),
                Arguments.of(missingVersion, CompressionAlgorithm.NONE, EncodingAlgorithm.NONE),
                Arguments.of(missingChecksum, CompressionAlgorithm.GZIP, EncodingAlgorithm.BASE64));
    }

    private static Stream<Arguments> receiveMessageAttributeCases() {
        List<String> codecAttributes = List.of(CodecAttributes.META);
        List<String> shopIdAndCodecAttributes = Stream.concat(
                Stream.of("shopId"),
                codecAttributes.stream())
                .toList();
        return Stream.of(
                Arguments.of(
                        "adds codec attributes when missing",
                        List.of(),
                        false,
                        codecAttributes),
                Arguments.of(
                        "adds codec attributes while preserving existing",
                        List.of("shopId"),
                        false,
                        shopIdAndCodecAttributes),
                Arguments.of(
                        "skips when codec attributes already present",
                        codecAttributes,
                        true,
                        codecAttributes),
                Arguments.of(
                        "skips when all attributes requested",
                        List.of("All"),
                        true,
                        List.of("All")));
    }

    private static Stream<Arguments> codecConfigurationPairs() {
        return Arrays.stream(CompressionAlgorithm.values())
                .flatMap(messageCompression -> Arrays.stream(EncodingAlgorithm.values())
                        .flatMap(messageEncoding -> Arrays.stream(CompressionAlgorithm.values())
                                .flatMap(interceptorCompression -> Arrays.stream(EncodingAlgorithm.values())
                                        .map(interceptorEncoding -> Arguments.of(
                                                messageCompression,
                                                messageEncoding,
                                                interceptorCompression,
                                                interceptorEncoding)))));
    }

    private static Stream<Arguments> checksumConfigurationPairs() {
        return Arrays.stream(ChecksumAlgorithm.values())
                .flatMap(messageChecksum -> Arrays.stream(ChecksumAlgorithm.values())
                        .map(interceptorChecksum -> Arguments.of(messageChecksum, interceptorChecksum)));
    }

    private static Stream<ChecksumAlgorithm> checksumAlgorithms() {
        return Stream.of(ChecksumAlgorithm.MD5, ChecksumAlgorithm.SHA256);
    }

    private static ChecksumAlgorithm otherChecksumAlgorithm(ChecksumAlgorithm checksumAlgorithm) {
        return checksumAlgorithm == ChecksumAlgorithm.MD5 ? ChecksumAlgorithm.SHA256 : ChecksumAlgorithm.MD5;
    }

    private static Stream<Arguments> unsupportedAttributeCases() {
        Map<String, MessageAttributeValue> unsupportedCompression = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=lz4;e=base64;h=md5"));
        Map<String, MessageAttributeValue> unsupportedEncoding = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=none;e=snappy;h=md5"));
        Map<String, MessageAttributeValue> unsupportedChecksum = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=crc32"));
        Map<String, MessageAttributeValue> unsupportedVersion = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=2;c=none;e=none;h=md5"));
        Map<String, MessageAttributeValue> invalidFormat = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c"));
        Map<String, MessageAttributeValue> duplicateKey = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=none;c=zstd"));

        return Stream.of(
                Arguments.of(
                        unsupportedCompression,
                        UnsupportedAlgorithmException.class,
                        "Unsupported payload compression: lz4"),
                Arguments.of(
                        unsupportedEncoding,
                        UnsupportedAlgorithmException.class,
                        "Unsupported payload encoding: snappy"),
                Arguments.of(
                        unsupportedChecksum,
                        UnsupportedAlgorithmException.class,
                        "Unsupported checksum algorithm: crc32"),
                Arguments.of(
                        unsupportedVersion,
                        UnsupportedCodecMetadataException.class,
                        "Unsupported codec version: 2"),
                Arguments.of(
                        invalidFormat,
                        UnsupportedCodecMetadataException.class,
                        "Unsupported codec metadata: v=1;c"),
                Arguments.of(
                        duplicateKey,
                        UnsupportedCodecMetadataException.class,
                        "Duplicate codec metadata key: c"));
    }

    private static Map<String, MessageAttributeValue> codecAttributes(
            byte[] payloadBytes,
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm,
            ChecksumAlgorithm checksumAlgorithm) {
        CodecConfiguration configuration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                compressionAlgorithm,
                encodingAlgorithm,
                checksumAlgorithm);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        CodecMetadataAttributeHandler.forOutbound(configuration, payloadBytes)
                .applyTo(attributes);
        return attributes;
    }

    private static Map<String, MessageAttributeValue> customAttributes(int count) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        for (int index = 0; index < count; index++) {
            attributes.put("custom-" + index, MessageAttributeUtils.stringAttribute("value-" + index));
        }
        return attributes;
    }

    private record ModifyRequestContext(SdkRequest request) implements Context.ModifyRequest {
    }

    private record ModifyResponseContext(SdkResponse response) implements Context.ModifyResponse {

        @Override
        public SdkRequest request() {
            return null;
        }

        @Override
        public SdkHttpRequest httpRequest() {
            return null;
        }

        @Override
        public Optional<RequestBody> requestBody() {
            return Optional.empty();
        }

        @Override
        public Optional<AsyncRequestBody> asyncRequestBody() {
            return Optional.empty();
        }

        @Override
        public SdkHttpResponse httpResponse() {
            return null;
        }

        @Override
        public Optional<Publisher<ByteBuffer>> responsePublisher() {
            return Optional.empty();
        }

        @Override
        public Optional<InputStream> responseBody() {
            return Optional.empty();
        }
    }
}
