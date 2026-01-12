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
import io.github.leanish.sqs.codec.attributes.MessageAttributeUtils;
import io.github.leanish.sqs.codec.attributes.PayloadChecksumAttributeHandler;
import io.github.leanish.sqs.codec.attributes.PayloadCodecAttributes;
import io.github.leanish.sqs.codec.attributes.PayloadCodecConfigurationAttributeHandler;
import io.github.leanish.sqs.codec.attributes.PayloadRawLengthAttributeHandler;
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
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class SqsPayloadCodecInterceptorTest {

    private static final String PAYLOAD = "{\"value\":42}";

    @Test
    void modifyRequestEncodesMessageBodyWithCompressionAndChecksum() {
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
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
                        PayloadCodecAttributes.COMPRESSION_ALG,
                        PayloadCodecAttributes.ENCODING_ALG,
                        PayloadCodecAttributes.CHECKSUM_ALG,
                        PayloadCodecAttributes.CHECKSUM,
                        PayloadCodecAttributes.VERSION,
                        PayloadCodecAttributes.RAW_LENGTH,
                        "shopId");
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.COMPRESSION_ALG).stringValue())
                .isEqualTo(CompressionAlgorithm.ZSTD.id());
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.ENCODING_ALG).stringValue())
                .isEqualTo(EncodingAlgorithm.BASE64.id());
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CHECKSUM_ALG).stringValue())
                .isEqualTo(ChecksumAlgorithm.MD5.id());
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.VERSION).dataType())
                .isEqualTo("Number");
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.VERSION).stringValue())
                .isEqualTo(Integer.toString(PayloadCodecAttributes.VERSION_VALUE));
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.RAW_LENGTH).dataType())
                .isEqualTo("Number");
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.RAW_LENGTH).stringValue())
                .isEqualTo(Integer.toString(PAYLOAD.getBytes(StandardCharsets.UTF_8).length));
        assertThat(encoded.messageBody()).isNotEqualTo(PAYLOAD);
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
        assertThat(new String(codec.decode(encoded.messageBody().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(PAYLOAD);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CHECKSUM).stringValue())
                .isEqualTo(ChecksumAlgorithm.MD5.digestor().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @MethodSource("skipEncodingCases")
    void modifyRequestSkipsWhenCodecAttributesArePresent(String attributeKey) {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(attributeKey, MessageAttributeUtils.stringAttribute("value")))
                .build();
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isSameAs(request);
    }

    @Test
    void modifyRequestDoesNotSkipWhenAttributesAreBlank() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        PayloadCodecAttributes.COMPRESSION_ALG,
                        MessageAttributeUtils.stringAttribute(" ")))
                .build();
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(SendMessageRequest.class);
        assertThat(modified).isNotSameAs(request);
        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.COMPRESSION_ALG).stringValue())
                .isEqualTo(CompressionAlgorithm.NONE.id());
    }

    @Test
    void modifyRequestUsesExplicitEncodingAlgorithm() {
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64_STD)
                .withChecksumAlgorithm(ChecksumAlgorithm.MD5);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.ENCODING_ALG).stringValue())
                .isEqualTo(EncodingAlgorithm.BASE64_STD.id());
    }

    @Test
    void modifyRequestPassesThroughUnknownRequests() {
        SdkRequest request = Mockito.mock(SdkRequest.class);

        SdkRequest modified = new SqsPayloadCodecInterceptor().modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isSameAs(request);
    }

    @Test
    void modifyRequestDoesNotIncludeChecksumWhenDisabled() {
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageBody()).isEqualTo(PAYLOAD);
        assertThat(encoded.messageAttributes())
                .containsKeys(
                        PayloadCodecAttributes.COMPRESSION_ALG,
                        PayloadCodecAttributes.ENCODING_ALG,
                        PayloadCodecAttributes.VERSION,
                        PayloadCodecAttributes.RAW_LENGTH);
        assertThat(encoded.messageAttributes())
                .doesNotContainKeys(
                        PayloadCodecAttributes.CHECKSUM_ALG,
                        PayloadCodecAttributes.CHECKSUM);
    }

    @Test
    void modifyRequestEncodesBatchEntries() {
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
                                        PayloadCodecAttributes.COMPRESSION_ALG,
                                        MessageAttributeUtils.stringAttribute(CompressionAlgorithm.ZSTD.id())))
                                .build())
                .build();
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
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
                        PayloadCodecAttributes.COMPRESSION_ALG,
                        PayloadCodecAttributes.ENCODING_ALG,
                        PayloadCodecAttributes.CHECKSUM_ALG,
                        PayloadCodecAttributes.CHECKSUM,
                        PayloadCodecAttributes.VERSION,
                        PayloadCodecAttributes.RAW_LENGTH);
        assertThat(encodedEntry.messageBody()).isNotEqualTo(PAYLOAD);
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE);
        assertThat(new String(codec.decode(encodedEntry.messageBody().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(PAYLOAD);
        assertThat(skippedEntry).isSameAs(request.entries().get(1));
    }

    @Test
    void modifyResponseDecodesMessageBody() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.GZIP, EncodingAlgorithm.BASE64_STD);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.GZIP,
                EncodingAlgorithm.BASE64_STD,
                ChecksumAlgorithm.MD5);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64_STD)
                .withChecksumAlgorithm(ChecksumAlgorithm.MD5);

        SdkResponse modified = interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) modified;
        assertThat(decoded.messages()).hasSize(1);
        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("checksumAlgorithms")
    void modifyResponseUsesAttributeAlgorithmsOverInterceptorConfig(ChecksumAlgorithm checksumAlgorithm) {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.GZIP, EncodingAlgorithm.BASE64_STD);
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String encodedBody = new String(codec.encode(payloadBytes), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payloadBytes,
                CompressionAlgorithm.GZIP,
                EncodingAlgorithm.BASE64_STD,
                checksumAlgorithm);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.NONE)
                .withChecksumAlgorithm(otherChecksumAlgorithm(checksumAlgorithm));

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages()).hasSize(1);
        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest(name = "message={0}/{1}, interceptor={2}/{3}")
    @MethodSource("codecConfigurationPairs")
    void modifyResponseUsesAttributeCodecAcrossConfigurations(
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
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
                .withCompressionAlgorithm(interceptorCompression)
                .withEncodingAlgorithm(interceptorEncoding)
                .withChecksumAlgorithm(ChecksumAlgorithm.SHA256);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages()).hasSize(1);
        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest(name = "message={0}, interceptor={1}")
    @MethodSource("checksumConfigurationPairs")
    void modifyResponseUsesAttributeChecksumAcrossConfigurations(
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
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64_STD)
                .withChecksumAlgorithm(interceptorChecksum);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages()).hasSize(1);
        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("checksumAlgorithms")
    void modifyResponseValidatesChecksumWhenCodecAttributesMissing(ChecksumAlgorithm checksumAlgorithm) {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(PayloadCodecAttributes.CHECKSUM_ALG, MessageAttributeUtils.stringAttribute(checksumAlgorithm.id()));
        attributes.put(PayloadCodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute(
                checksumAlgorithm.digestor().checksum(payloadBytes)));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
                .withChecksumAlgorithm(otherChecksumAlgorithm(checksumAlgorithm));

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponseDecodesMultipleMessages() {
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

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages()).hasSize(2);
        assertThat(decoded.messages().get(0).body()).isEqualTo(PAYLOAD);
        assertThat(decoded.messages().get(1)).isSameAs(plainMessage);
    }

    @Test
    void modifyResponsePassesThroughWhenNoCodecAttributes() {
        Message message = Message.builder()
                .body(PAYLOAD)
                .build();
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(message)
                .build();
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor();

        SdkResponse modified = interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) modified;
        assertThat(decoded.messages().getFirst()).isSameAs(message);
        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponseReturnsSameWhenNoMessages() {
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(List.of())
                .build();

        SdkResponse modified = new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(modified).isSameAs(response);
    }

    @Test
    void modifyResponsePassesThroughUnknownResponses() {
        SdkResponse response = Mockito.mock(SdkResponse.class);

        SdkResponse modified = new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(modified).isSameAs(response);
    }

    @Test
    void modifyResponseSkipsChecksumWhenDisabled() {
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
        SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64)
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponseThrowsWhenChecksumAlgorithmIsNoneButChecksumPresent() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        attributes.put(PayloadCodecAttributes.CHECKSUM_ALG, MessageAttributeUtils.stringAttribute(ChecksumAlgorithm.NONE.id()));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM_ALG);
    }

    @Test
    void modifyResponseAcceptsCaseInsensitiveAlgorithmAttributes() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.GZIP, EncodingAlgorithm.BASE64_STD);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute("GZIP"));
        attributes.put(PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute("BASE64-STD"));
        attributes.put(PayloadCodecAttributes.VERSION, MessageAttributeUtils.numberAttribute(PayloadCodecAttributes.VERSION_VALUE));
        attributes.put(PayloadCodecAttributes.RAW_LENGTH,
                MessageAttributeUtils.numberAttribute(PAYLOAD.getBytes(StandardCharsets.UTF_8).length));
        attributes.put(PayloadCodecAttributes.CHECKSUM_ALG, MessageAttributeUtils.stringAttribute(ChecksumAlgorithm.MD5.id()));
        attributes.put(PayloadCodecAttributes.CHECKSUM, MessageAttributeUtils.stringAttribute(
                ChecksumAlgorithm.MD5.digestor().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8))));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("defaultedAttributeCases")
    void modifyResponseDefaultsMissingCodecAttributes(
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

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) new SqsPayloadCodecInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("unsupportedAttributeCases")
    void modifyResponseThrowsWhenUnsupportedAttributes(
            Map<String, MessageAttributeValue> attributes,
            String expectedMessage) {
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage(expectedMessage);
    }

    @Test
    void modifyResponseThrowsWhenChecksumAlgorithmMissing() {
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        attributes.remove(PayloadCodecAttributes.CHECKSUM_ALG);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM_ALG);
    }

    @Test
    void modifyResponseThrowsWhenChecksumAlgorithmMissingWithoutCodecAttributes() {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = Map.of(
                PayloadCodecAttributes.CHECKSUM,
                MessageAttributeUtils.stringAttribute(ChecksumAlgorithm.MD5.digestor().checksum(payloadBytes)));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM_ALG);
    }

    @Test
    void modifyResponseThrowsWhenChecksumAlgorithmBlank() {
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.MD5);
        attributes.put(PayloadCodecAttributes.CHECKSUM_ALG, MessageAttributeUtils.stringAttribute(" "));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(PAYLOAD)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM_ALG);
    }

    @Test
    void modifyResponseThrowsWhenChecksumMissing() {
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

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM);
    }

    @Test
    void modifyResponseThrowsWhenChecksumBlank() {
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

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM);
    }

    @Test
    void modifyResponseThrowsWhenChecksumMismatches() {
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

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Payload checksum mismatch");
    }

    @Test
    void modifyResponseThrowsWhenInvalidBase64Payload() {
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

        assertThatThrownBy(() -> new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Invalid base64 payload");
    }

    @Test
    void modifyResponseUsesChecksumAlgorithmFromAttributes() {
        PayloadCodec codec = new PayloadCodec(CompressionAlgorithm.NONE, EncodingAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.SHA256);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) new SqsPayloadCodecInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(decoded.messages().getFirst().body()).isEqualTo(PAYLOAD);
    }

    @Test
    void compressionFromAttributeValueRejectsBlank() {
        String value = " ";
        assertThatThrownBy(() -> PayloadCodecConfigurationAttributeHandler.compressionFromAttributeValue(value))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Unsupported payload compression: " + value);
    }

    @Test
    void encodingFromAttributeValueRejectsBlank() {
        String value = " ";
        assertThatThrownBy(() -> PayloadCodecConfigurationAttributeHandler.encodingFromAttributeValue(value))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Unsupported payload encoding: " + value);
    }

    private static Stream<Arguments> skipEncodingCases() {
        return Stream.of(
                Arguments.of(PayloadCodecAttributes.COMPRESSION_ALG),
                Arguments.of(PayloadCodecAttributes.ENCODING_ALG));
    }

    private static Stream<Arguments> defaultedAttributeCases() {
        Map<String, MessageAttributeValue> emptyAttributes = Map.of();
        Map<String, MessageAttributeValue> missingCompression = Map.of(
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute(EncodingAlgorithm.BASE64.id()));
        Map<String, MessageAttributeValue> missingEncoding = Map.of(
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute(CompressionAlgorithm.ZSTD.id()));
        Map<String, MessageAttributeValue> blankCompression = Map.of(
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute("  "),
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute(EncodingAlgorithm.BASE64.id()));
        Map<String, MessageAttributeValue> blankEncoding = Map.of(
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute(CompressionAlgorithm.ZSTD.id()),
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute(""));
        Map<String, MessageAttributeValue> missingVersion = Map.of(
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute(CompressionAlgorithm.NONE.id()),
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute(EncodingAlgorithm.NONE.id()));
        Map<String, MessageAttributeValue> blankVersion = Map.of(
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute(CompressionAlgorithm.NONE.id()),
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute(EncodingAlgorithm.NONE.id()),
                PayloadCodecAttributes.VERSION, MessageAttributeUtils.stringAttribute(" "));

        return Stream.of(
                Arguments.of(emptyAttributes, CompressionAlgorithm.NONE, EncodingAlgorithm.NONE),
                Arguments.of(missingCompression, CompressionAlgorithm.NONE, EncodingAlgorithm.BASE64),
                Arguments.of(missingEncoding, CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE),
                Arguments.of(blankCompression, CompressionAlgorithm.NONE, EncodingAlgorithm.BASE64),
                Arguments.of(blankEncoding, CompressionAlgorithm.ZSTD, EncodingAlgorithm.NONE),
                Arguments.of(missingVersion, CompressionAlgorithm.NONE, EncodingAlgorithm.NONE),
                Arguments.of(blankVersion, CompressionAlgorithm.NONE, EncodingAlgorithm.NONE));
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
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute("lz4"),
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute(EncodingAlgorithm.BASE64.id()),
                PayloadCodecAttributes.VERSION, MessageAttributeUtils.numberAttribute(PayloadCodecAttributes.VERSION_VALUE));
        Map<String, MessageAttributeValue> unsupportedEncoding = Map.of(
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute(CompressionAlgorithm.NONE.id()),
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute("snappy"),
                PayloadCodecAttributes.VERSION, MessageAttributeUtils.numberAttribute(PayloadCodecAttributes.VERSION_VALUE));
        Map<String, MessageAttributeValue> compressionWithoutEncoding = Map.of(
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute(CompressionAlgorithm.GZIP.id()),
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute(EncodingAlgorithm.NONE.id()),
                PayloadCodecAttributes.VERSION, MessageAttributeUtils.numberAttribute(PayloadCodecAttributes.VERSION_VALUE));
        Map<String, MessageAttributeValue> unsupportedVersion = Map.of(
                PayloadCodecAttributes.COMPRESSION_ALG, MessageAttributeUtils.stringAttribute(CompressionAlgorithm.NONE.id()),
                PayloadCodecAttributes.ENCODING_ALG, MessageAttributeUtils.stringAttribute(EncodingAlgorithm.NONE.id()),
                PayloadCodecAttributes.VERSION, MessageAttributeUtils.numberAttribute(2));

        return Stream.of(
                Arguments.of(
                        unsupportedCompression,
                        "Unsupported payload compression: lz4"),
                Arguments.of(
                        unsupportedEncoding,
                        "Unsupported payload encoding: snappy"),
                Arguments.of(
                        compressionWithoutEncoding,
                        "Unsupported payload encoding: none"),
                Arguments.of(
                        unsupportedVersion,
                        "Unsupported codec version: 2"));
    }

    private static Map<String, MessageAttributeValue> codecAttributes(
            byte[] payloadBytes,
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm,
            ChecksumAlgorithm checksumAlgorithm) {
        PayloadCodecConfiguration configuration = new PayloadCodecConfiguration(
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
