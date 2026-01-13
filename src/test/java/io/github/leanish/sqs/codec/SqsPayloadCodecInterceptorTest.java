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
import io.github.leanish.sqs.codec.attributes.MessageAttributeUtils;
import io.github.leanish.sqs.codec.attributes.PayloadChecksumAttributeHandler;
import io.github.leanish.sqs.codec.attributes.PayloadCodecAttributes;
import io.github.leanish.sqs.codec.attributes.PayloadCodecConfigurationAttributeHandler;
import io.github.leanish.sqs.codec.attributes.PayloadRawLengthAttributeHandler;
import io.github.leanish.sqs.codec.attributes.UnsupportedCodecConfigurationException;
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

class SqsPayloadCodecInterceptorTest {

    private static final String PAYLOAD = "{\"value\":42}";

    @Test
    void modifyRequest_happyCase() {
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD);
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
                        PayloadCodecAttributes.CONF,
                        PayloadCodecAttributes.CHECKSUM,
                        PayloadCodecAttributes.RAW_LENGTH,
                        "shopId");
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CONF).stringValue())
                .isEqualTo("v=1;c=zstd;e=base64;h=md5");
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.RAW_LENGTH).dataType())
                .isEqualTo("Number");
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.RAW_LENGTH).stringValue())
                .isEqualTo(Integer.toString(PAYLOAD.getBytes(StandardCharsets.UTF_8).length));
        assertThat(encoded.messageBody())
                .isNotEqualTo(PAYLOAD);
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
        assertThat(new String(codec.decode(encoded.messageBody().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(PAYLOAD);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CHECKSUM).stringValue())
                .isEqualTo(ChecksumAlgorithm.MD5.digestor().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void modifyRequest_alreadyPresentAttributes() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        PayloadCodecAttributes.CONF,
                        MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=md5")))
                .build();
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor();

        SdkRequest modified = interceptor.modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        assertThat(modified)
                .isSameAs(request);
    }

    @Test
    void modifyRequest_invalidConfiguration() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        PayloadCodecAttributes.CONF,
                        MessageAttributeUtils.stringAttribute("v=2;c=none;e=none;h=md5")))
                .build();

        assertThatThrownBy(() -> SqsPayloadCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(UnsupportedCodecConfigurationException.class)
                .hasMessage("Unsupported codec version: 2");
    }

    @Test
    void modifyRequest_blankAttributes() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        PayloadCodecAttributes.CONF,
                        MessageAttributeUtils.stringAttribute(" ")))
                .build();
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(SendMessageRequest.class);
        assertThat(modified).isNotSameAs(request);
        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CONF).stringValue())
                .isEqualTo("v=1;c=none;e=none;h=md5");
    }

    @Test
    void modifyRequest_explicitEncoding() {
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64_STD)
                .withChecksumAlgorithm(ChecksumAlgorithm.MD5);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SdkRequest modified = interceptor.modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CONF).stringValue())
                .isEqualTo("v=1;c=zstd;e=base64-std;h=md5");
    }

    @Test
    void modifyRequest_unknownRequests() {
        SdkRequest request = Mockito.mock(SdkRequest.class);

        SdkRequest modified = SqsPayloadCodecInterceptor.defaultInterceptor()
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

        SdkRequest modified = SqsPayloadCodecInterceptor.defaultInterceptor()
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
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor()
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        assertThat(encoded.messageBody()).isEqualTo(PAYLOAD);
        assertThat(encoded.messageAttributes())
                .containsKeys(
                        PayloadCodecAttributes.CONF,
                        PayloadCodecAttributes.RAW_LENGTH);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CONF).stringValue())
                .isEqualTo("v=1;c=none;e=none;h=none");
        assertThat(encoded.messageAttributes())
                .doesNotContainKeys(PayloadCodecAttributes.CHECKSUM);
    }

    @Test
    void modifyRequest_batch() {
        SendMessageBatchRequest request = SendMessageBatchRequest.builder()
                .entries(
                        SendMessageBatchRequestEntry.builder()
                                .id("encoded")
                                .messageBody(PAYLOAD)
                                .messageAttributes(Map.of("shopId", MessageAttributeUtils.stringAttribute("shop-1")))
                                .build(),
                        SendMessageBatchRequestEntry.builder()
                                .id("skipped")
                                .messageBody("skip")
                                .messageAttributes(Map.of(
                                        PayloadCodecAttributes.CONF,
                                        MessageAttributeUtils.stringAttribute("v=1;c=zstd;e=base64;h=md5")))
                                .build())
                .build();
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.NONE);

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
                .containsKeys(
                        PayloadCodecAttributes.CONF,
                        PayloadCodecAttributes.CHECKSUM,
                        PayloadCodecAttributes.RAW_LENGTH);
        assertThat(encodedEntry.messageBody()).isNotEqualTo(PAYLOAD);
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
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
        PayloadCodec codec = new PayloadCodec(messageCompression, messageEncoding);
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
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor()
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
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
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
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor()
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
    void modifyResponsemissingCodecAttributes(ChecksumAlgorithm checksumAlgorithm) {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;h=" + checksumAlgorithm.id()));
        attributes.put(
                PayloadCodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute(checksumAlgorithm.digestor().checksum(payloadBytes)));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor()
                .withChecksumAlgorithm(otherChecksumAlgorithm(checksumAlgorithm));

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_multipleMessages() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.NONE, EncodingAlgorithm.BASE64);
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

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsPayloadCodecInterceptor.defaultInterceptor()
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

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsPayloadCodecInterceptor.defaultInterceptor()
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

        SdkResponse modified = SqsPayloadCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(modified)
                .isSameAs(response);
    }

    @Test
    void modifyResponse_emptyResponses() {
        SdkResponse response = Mockito.mock(SdkResponse.class);

        SdkResponse modified = SqsPayloadCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(modified)
                .isSameAs(response);
    }

    @Test
    void modifyResponse_disabledChecksum() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.BASE64);
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
        SqsPayloadCodecInterceptor interceptor = SqsPayloadCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64)
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_noneAlgorithmYetPresentChecksum() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        attributes.put(PayloadCodecAttributes.CONF,
                MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=none"));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsPayloadCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(ChecksumValidationException.class)
                .hasMessage("Missing required checksum algorithm");
    }

    @Test
    void modifyResponse_caseInsensitiveAlgorithm() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.GZIP, EncodingAlgorithm.BASE64_STD);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(PayloadCodecAttributes.CONF,
                MessageAttributeUtils.stringAttribute("V=1;C=GZIP;E=BASE64-STD;H=MD5"));
        attributes.put(PayloadCodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute(
                ChecksumAlgorithm.MD5.digestor().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8))));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsPayloadCodecInterceptor.defaultInterceptor().modifyResponse(
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
        PayloadCodec codec = new PayloadCodec(compressionAlgorithm, encodingAlgorithm);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsPayloadCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("unsupportedAttributeCases")
    void modifyResponse_unsupportedAttributes(
            Map<String, MessageAttributeValue> attributes,
            Class<? extends PayloadCodecException> expectedException,
            String expectedMessage) {
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsPayloadCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(expectedException)
                .hasMessage(expectedMessage);
    }

    @Test
    void modifyResponse_missingChecksumAlgorithm() {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = Map.of(
                PayloadCodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute(ChecksumAlgorithm.MD5.digestor().checksum(payloadBytes)));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsPayloadCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(ChecksumValidationException.class)
                .hasMessage("Missing required checksum algorithm");
    }

    @Test
    void modifyResponse_missingChecksum() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        attributes.remove(PayloadCodecAttributes.CHECKSUM);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsPayloadCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOfSatisfying(ChecksumValidationException.class, exception -> {
                    assertThat(exception.detail()).isEqualTo(PayloadCodecAttributes.CHECKSUM);
                })
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM);
    }

    @Test
    void modifyResponse_blankChecksum() {
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        attributes.put(PayloadCodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute(" "));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsPayloadCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOfSatisfying(ChecksumValidationException.class, exception -> {
                    assertThat(exception.detail()).isEqualTo(PayloadCodecAttributes.CHECKSUM);
                })
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM);
    }

    @Test
    void modifyResponse_checksumMismatch() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        attributes.put(PayloadCodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute("bad"));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> SqsPayloadCodecInterceptor.defaultInterceptor().modifyResponse(
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

        assertThatThrownBy(() -> SqsPayloadCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(InvalidPayloadException.class)
                .hasMessage("Invalid base64 payload");
    }

    private static Stream<Arguments> defaultedAttributeCases() {
        Map<String, MessageAttributeValue> emptyAttributes = Map.of();
        Map<String, MessageAttributeValue> missingCompression = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;e=base64"));
        Map<String, MessageAttributeValue> missingEncoding = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;c=zstd"));
        Map<String, MessageAttributeValue> missingVersion = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("c=none;e=none;h=none"));
        Map<String, MessageAttributeValue> missingChecksum = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;c=gzip;e=base64"));

        return Stream.of(
                Arguments.of(emptyAttributes, CompressionAlgorithm.NONE, EncodingAlgorithm.NONE),
                Arguments.of(missingCompression, CompressionAlgorithm.NONE, EncodingAlgorithm.BASE64),
                Arguments.of(missingEncoding, CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE),
                Arguments.of(missingVersion, CompressionAlgorithm.NONE, EncodingAlgorithm.NONE),
                Arguments.of(missingChecksum, CompressionAlgorithm.GZIP, EncodingAlgorithm.BASE64));
    }

    private static Stream<Arguments> receiveMessageAttributeCases() {
        List<String> codecAttributes = List.of(
                PayloadCodecAttributes.CONF,
                PayloadCodecAttributes.CHECKSUM,
                PayloadCodecAttributes.RAW_LENGTH);
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
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;c=lz4;e=base64;h=md5"));
        Map<String, MessageAttributeValue> unsupportedEncoding = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;c=none;e=snappy;h=md5"));
        Map<String, MessageAttributeValue> unsupportedChecksum = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=crc32"));
        Map<String, MessageAttributeValue> unsupportedVersion = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=2;c=none;e=none;h=md5"));
        Map<String, MessageAttributeValue> invalidFormat = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;c"));
        Map<String, MessageAttributeValue> duplicateKey = Map.of(
                PayloadCodecAttributes.CONF, MessageAttributeUtils.stringAttribute("v=1;c=none;c=zstd"));

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
                        UnsupportedCodecConfigurationException.class,
                        "Unsupported codec version: 2"),
                Arguments.of(
                        invalidFormat,
                        UnsupportedCodecConfigurationException.class,
                        "Unsupported codec configuration: v=1;c"),
                Arguments.of(
                        duplicateKey,
                        UnsupportedCodecConfigurationException.class,
                        "Duplicate codec configuration key: c"));
    }

    private static Map<String, MessageAttributeValue> codecAttributes(
            byte[] payloadBytes,
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm,
            ChecksumAlgorithm checksumAlgorithm) {
        PayloadCodecConfiguration configuration = new PayloadCodecConfiguration(
                PayloadCodecAttributes.VERSION_VALUE,
                compressionAlgorithm,
                encodingAlgorithm,
                checksumAlgorithm);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        PayloadCodecConfigurationAttributeHandler.forOutbound(configuration)
                .applyTo(attributes);
        PayloadRawLengthAttributeHandler.forOutbound(payloadBytes.length)
                .applyTo(attributes);
        PayloadChecksumAttributeHandler.forOutbound(checksumAlgorithm, payloadBytes)
                .applyTo(attributes);
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
