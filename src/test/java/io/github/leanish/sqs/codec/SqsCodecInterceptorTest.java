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
import io.github.leanish.sqs.codec.algorithms.compression.CompressionException;
import io.github.leanish.sqs.codec.algorithms.encoding.Base64PayloadCodec;
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
                .withSkipCompressionWhenLarger(false);
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
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);
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
                .isEqualTo("v=1;c=none;e=none;h=md5;s=" + expectedChecksum);
        assertThat(encoded.messageAttributes())
                .containsOnlyKeys(CodecAttributes.META);
    }

    @Test
    void modifyRequest_prefersOriginalPayloadWhenCompressedPayloadHasEqualSize() {
        String payload = payloadWithEqualEncodedLengthForGzip();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(payload)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(encoded.messageBody()).isEqualTo(payload);
        String expectedChecksum = ChecksumAlgorithm.MD5.implementation().checksum(payloadBytes);
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=none;e=none;h=md5;s=" + expectedChecksum);
    }

    @Test
    void modifyRequest_includesRawLengthMetadataWithoutChecksum() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE)
                .withSkipCompressionWhenLarger(false);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(encoded.messageAttributes())
                .containsKey(CodecAttributes.META);
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=zstd;e=base64;h=none;l=12");
    }

    @Test
    void modifyRequest_omitsRawLengthMetadataWhenConfigured() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withSkipCompressionWhenLarger(false)
                .withIncludeRawPayloadLength(false);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        String expectedChecksum = ChecksumAlgorithm.MD5.implementation().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=zstd;e=base64;h=md5;s=" + expectedChecksum);
    }

    @Test
    void modifyRequest_keepsCompressedPayloadWhenItIsSmaller() {
        String compressiblePayload = "a".repeat(2048);
        int payloadLength = compressiblePayload.getBytes(StandardCharsets.UTF_8).length;
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(compressiblePayload)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        String expectedChecksum = ChecksumAlgorithm.MD5.implementation().checksum(compressiblePayload.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=zstd;e=base64;h=md5;s=" + expectedChecksum + ";l=" + payloadLength);
        assertThat(encoded.messageBody()).isNotEqualTo(compressiblePayload);
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);
        assertThat(new String(codec.decode(encoded.messageBody().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(compressiblePayload);
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
    void modifyRequest_explicitEncodingWithoutCompression() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.NONE)
                .withEncodingAlgorithm(EncodingAlgorithm.BASE64)
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(encoded.messageBody()).isEqualTo(encodedPlainPayload(PAYLOAD));
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=none;e=base64;h=none");
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
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Codec metadata must enable compression, encoding or checksum");
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
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Codec metadata must enable compression, encoding or checksum");
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

    @ParameterizedTest
    @MethodSource("invalidCompressedPayloadCases")
    void modifyRequest_alreadyPresentAttributes_invalidCompressedPayload(
            CompressionAlgorithm compressionAlgorithm,
            String encodedPayload,
            String expectedMessage) {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(encodedPayload)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=1;c=" + compressionAlgorithm.id() + ";e=base64;h=none")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(CompressionException.class)
                .hasMessage(expectedMessage);
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
    void modifyRequest_missingVersionInConfiguration() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("c=zstd;e=base64;h=none;l=12")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Missing required codec metadata key: v");
    }

    @Test
    void modifyRequest_noOpMetadataConfiguration() {
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=none")))
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor()
                .modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes()))
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Codec metadata must enable compression, encoding or checksum");
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
    void modifyRequest_explicitCompression() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withChecksumAlgorithm(ChecksumAlgorithm.MD5)
                .withSkipCompressionWhenLarger(false);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SdkRequest modified = interceptor.modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        SendMessageRequest encoded = (SendMessageRequest) modified;
        String expectedChecksum = ChecksumAlgorithm.MD5.implementation().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertThat(encoded.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=zstd;e=base64;h=md5;s=" + expectedChecksum + ";l=12");
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
        assertThat(encoded.messageAttributes()).isEmpty();
    }

    @Test
    void modifyRequest_skippedCompressionWithoutChecksumOmitsMetadata() {
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(PAYLOAD)
                .build();

        SendMessageRequest encoded = (SendMessageRequest) interceptor.modifyRequest(new ModifyRequestContext(request), new ExecutionAttributes());

        assertThat(encoded.messageBody()).isEqualTo(PAYLOAD);
        assertThat(encoded.messageAttributes()).isEmpty();
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
        Codec skippedCodec = new Codec(CompressionAlgorithm.ZSTD);
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
                .withSkipCompressionWhenLarger(false);

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
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);
        assertThat(new String(codec.decode(encodedEntry.messageBody().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(PAYLOAD);
        assertThat(skippedEntry)
                .isSameAs(request.entries().get(1));
    }

    @Test
    void modifyRequest_batch_prefersOriginalPayloadPerEntryWhenCompressedPayloadIsLarger() {
        String compressiblePayload = "a".repeat(2048);
        byte[] compressiblePayloadBytes = compressiblePayload.getBytes(StandardCharsets.UTF_8);
        byte[] incompressiblePayloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        SendMessageBatchRequest request = SendMessageBatchRequest.builder()
                .entries(
                        SendMessageBatchRequestEntry.builder()
                                .id("incompressible")
                                .messageBody(PAYLOAD)
                                .build(),
                        SendMessageBatchRequestEntry.builder()
                                .id("compressible")
                                .messageBody(compressiblePayload)
                                .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD);

        SendMessageBatchRequest encoded = (SendMessageBatchRequest) interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());
        SendMessageBatchRequestEntry incompressibleEntry = encoded.entries().stream()
                .filter(entry -> "incompressible".equals(entry.id()))
                .findFirst()
                .orElseThrow();
        SendMessageBatchRequestEntry compressibleEntry = encoded.entries().stream()
                .filter(entry -> "compressible".equals(entry.id()))
                .findFirst()
                .orElseThrow();

        String incompressibleChecksum = ChecksumAlgorithm.MD5.implementation().checksum(incompressiblePayloadBytes);
        assertThat(incompressibleEntry.messageBody()).isEqualTo(PAYLOAD);
        assertThat(incompressibleEntry.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=none;e=none;h=md5;s=" + incompressibleChecksum);

        String compressibleChecksum = ChecksumAlgorithm.MD5.implementation().checksum(compressiblePayloadBytes);
        assertThat(compressibleEntry.messageBody()).isNotEqualTo(compressiblePayload);
        assertThat(compressibleEntry.messageAttributes().get(CodecAttributes.META).stringValue())
                .isEqualTo("v=1;c=zstd;e=base64;h=md5;s=" + compressibleChecksum + ";l=" + compressiblePayloadBytes.length);
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);
        assertThat(new String(codec.decode(compressibleEntry.messageBody().getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                .isEqualTo(compressiblePayload);
    }

    @ParameterizedTest(name = "message={0}, interceptor={1}")
    @MethodSource("codecConfigurationPairs")
    void modifyResponse_codecConfigurationPairs(
            CompressionAlgorithm messageCompression,
            CompressionAlgorithm interceptorCompression) {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Codec codec = new Codec(messageCompression);
        String encodedBody = new String(codec.encode(payloadBytes), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payloadBytes,
                messageCompression,
                ChecksumAlgorithm.NONE);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(interceptorCompression)
                .withChecksumAlgorithm(ChecksumAlgorithm.SHA256);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages())
                .hasSize(1);
        assertThat(decoded.messages().get(0).body())
                .isEqualTo(PAYLOAD);
    }

    @ParameterizedTest(name = "message={0}, interceptor={1}")
    @MethodSource("checksumConfigurationPairs")
    void modifyResponse_checksumConfigurationPairs(
            ChecksumAlgorithm messageChecksum,
            ChecksumAlgorithm interceptorChecksum) {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);
        String encodedBody = new String(codec.encode(payloadBytes), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payloadBytes,
                CompressionAlgorithm.ZSTD,
                messageChecksum);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.GZIP)
                .withChecksumAlgorithm(interceptorChecksum);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages())
                .hasSize(1);
        assertThat(decoded.messages().get(0).body())
                .isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("checksumAlgorithms")
    void modifyResponse_missingCompressionAttribute(ChecksumAlgorithm checksumAlgorithm) {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute(
                                "v=1;e=none;h=" + checksumAlgorithm.id()
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

        assertThat(decoded.messages().get(0).body())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_multipleMessages() {
        Codec codec = new Codec(CompressionAlgorithm.GZIP);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Message encodedMessage = Message.builder()
                .body(encodedBody)
                .messageAttributes(codecAttributes(
                        PAYLOAD.getBytes(StandardCharsets.UTF_8),
                        CompressionAlgorithm.GZIP,
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

        assertThat(decoded.messages().get(0))
                .isSameAs(message);
        assertThat(decoded.messages().get(0).body())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_unknownMetadataKeyWithoutCompressionLeavesBodyAsIs() {
        String encodedBody = new String(
                Base64PayloadCodec.instance().encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        byte[] encodedBodyBytes = encodedBody.getBytes(StandardCharsets.UTF_8);
        Message message = Message.builder()
                .body(encodedBody)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute(
                                "v=1;c=none;e=none;x=base64;h=md5;s="
                                        + ChecksumAlgorithm.MD5.implementation().checksum(encodedBodyBytes))))
                .build();
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(message)
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().get(0)).isSameAs(message);
        assertThat(decoded.messages().get(0).body()).isEqualTo(encodedBody);
    }

    @Test
    void modifyResponse_encodingOnlyPayload() {
        Codec codec = new Codec(CompressionAlgorithm.NONE, EncodingAlgorithm.BASE64);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Message message = Message.builder()
                .body(encodedBody)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute("v=1;c=none;e=base64;h=none")))
                .build();
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(message)
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().get(0).body()).isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_legacyCompressedPayloadWithoutEncodingMetadata() {
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);
        String encodedBody = new String(codec.encode(payloadBytes), StandardCharsets.UTF_8);
        Message message = Message.builder()
                .body(encodedBody)
                .messageAttributes(Map.of(
                        CodecAttributes.META,
                        MessageAttributeUtils.stringAttribute(
                                "v=1;c=zstd;h=md5;s="
                                        + ChecksumAlgorithm.MD5.implementation().checksum(payloadBytes)
                                        + ";l="
                                        + payloadBytes.length)))
                .build();
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(message)
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().get(0).body()).isEqualTo(PAYLOAD);
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
        Codec codec = new Codec(CompressionAlgorithm.ZSTD);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.ZSTD,
                ChecksumAlgorithm.NONE);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();
        SqsCodecInterceptor interceptor = SqsCodecInterceptor.defaultInterceptor()
                .withCompressionAlgorithm(CompressionAlgorithm.ZSTD)
                .withChecksumAlgorithm(ChecksumAlgorithm.NONE);

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) interceptor.modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().get(0).body())
                .isEqualTo(PAYLOAD);
    }

    @Test
    void modifyResponse_noneAlgorithmYetPresentChecksum() {
        Codec codec = new Codec(CompressionAlgorithm.NONE);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                PAYLOAD.getBytes(StandardCharsets.UTF_8),
                CompressionAlgorithm.NONE,
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
                .isInstanceOf(UnsupportedCodecMetadataException.class)
                .hasMessage("Codec metadata must enable compression, encoding or checksum");
    }

    @Test
    void modifyResponse_caseInsensitiveAlgorithm() {
        Codec codec = new Codec(CompressionAlgorithm.GZIP);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(CodecAttributes.META,
                MessageAttributeUtils.stringAttribute(
                        "V=1;C=GZIP;E=BASE64;H=MD5;L=12;S="
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

        assertThat(decoded.messages().get(0).body()).isEqualTo(PAYLOAD);
    }

    @ParameterizedTest
    @MethodSource("defaultedAttributeCases")
    void modifyResponse_missingCodecAttributes(
            Map<String, MessageAttributeValue> attributes,
            CompressionAlgorithm compressionAlgorithm) {
        Codec codec = new Codec(compressionAlgorithm);
        String encodedBody = new String(codec.encode(PAYLOAD.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        ReceiveMessageResponse decoded = (ReceiveMessageResponse) SqsCodecInterceptor.defaultInterceptor()
                .modifyResponse(new ModifyResponseContext(response), new ExecutionAttributes());

        assertThat(decoded.messages().get(0).body()).isEqualTo(PAYLOAD);
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
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String encodedBody = PAYLOAD;
        Map<String, MessageAttributeValue> attributes = Map.of(
                CodecAttributes.META,
                MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=md5;l=" + payloadBytes.length));
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
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        Map<String, MessageAttributeValue> attributes = Map.of(
                CodecAttributes.META,
                MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=md5;s=;l=" + payloadBytes.length));
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
        byte[] payloadBytes = PAYLOAD.getBytes(StandardCharsets.UTF_8);
        String encodedBody = PAYLOAD;
        Map<String, MessageAttributeValue> attributes = Map.of(
                CodecAttributes.META,
                MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=md5;s=bad;l=" + payloadBytes.length));
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
                CompressionAlgorithm.ZSTD,
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

    @ParameterizedTest
    @MethodSource("invalidCompressedPayloadCases")
    void modifyResponse_invalidCompressedPayload(
            CompressionAlgorithm compressionAlgorithm,
            String encodedPayload,
            String expectedMessage) {
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedPayload)
                        .messageAttributes(Map.of(
                                CodecAttributes.META,
                                MessageAttributeUtils.stringAttribute("v=1;c=" + compressionAlgorithm.id() + ";e=base64;h=none")))
                        .build())
                .build();

        assertThatThrownBy(() -> SqsCodecInterceptor.defaultInterceptor().modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(CompressionException.class)
                .hasMessage(expectedMessage);
    }

    private static Stream<Arguments> defaultedAttributeCases() {
        Map<String, MessageAttributeValue> emptyAttributes = Map.of();
        Map<String, MessageAttributeValue> missingChecksum = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=gzip;e=base64;l=12"));
        Map<String, MessageAttributeValue> missingCompression = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;e=none;h=md5;s="
                        + ChecksumAlgorithm.MD5.implementation().checksum(PAYLOAD.getBytes(StandardCharsets.UTF_8))));

        return Stream.of(
                Arguments.of(emptyAttributes, CompressionAlgorithm.NONE),
                Arguments.of(missingCompression, CompressionAlgorithm.NONE),
                Arguments.of(missingChecksum, CompressionAlgorithm.GZIP));
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

    private static Stream<Arguments> invalidCompressedPayloadCases() {
        return Stream.of(
                Arguments.of(
                        CompressionAlgorithm.GZIP,
                        encodedGarbagePayload("not-gzip"),
                        "Failed to decompress payload with gzip"),
                Arguments.of(
                        CompressionAlgorithm.SNAPPY,
                        encodedGarbagePayload("not-snappy"),
                        "Failed to decompress payload with snappy"),
                Arguments.of(
                        CompressionAlgorithm.ZSTD,
                        encodedGarbagePayload("not-zstd"),
                        "Failed to decompress payload with zstd"));
    }

    private static String encodedGarbagePayload(String payload) {
        return Base64PayloadCodec.instance().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static Stream<Arguments> codecConfigurationPairs() {
        return Arrays.stream(CompressionAlgorithm.values())
                .flatMap(messageCompression -> Arrays.stream(CompressionAlgorithm.values())
                        .map(interceptorCompression -> Arguments.of(
                                messageCompression,
                                interceptorCompression)));
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
        Map<String, MessageAttributeValue> unsupportedChecksum = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=crc32"));
        Map<String, MessageAttributeValue> unsupportedEncoding = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=none;e=ascii85;h=md5"));
        Map<String, MessageAttributeValue> unsupportedVersion = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=2;c=none;e=none;h=md5"));
        Map<String, MessageAttributeValue> missingVersion = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("c=zstd;e=base64;h=none;l=12"));
        Map<String, MessageAttributeValue> noOpMetadata = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;h=none"));
        Map<String, MessageAttributeValue> invalidFormat = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c"));
        Map<String, MessageAttributeValue> duplicateKey = Map.of(
                CodecAttributes.META, MessageAttributeUtils.stringAttribute("v=1;c=none;e=none;c=zstd"));

        return Stream.of(
                Arguments.of(
                        unsupportedCompression,
                        UnsupportedAlgorithmException.class,
                        "Unsupported payload compression: lz4"),
                Arguments.of(
                        unsupportedChecksum,
                        UnsupportedAlgorithmException.class,
                        "Unsupported checksum algorithm: crc32"),
                Arguments.of(
                        unsupportedEncoding,
                        UnsupportedAlgorithmException.class,
                        "Unsupported payload encoding: ascii85"),
                Arguments.of(
                        unsupportedVersion,
                        UnsupportedCodecMetadataException.class,
                        "Unsupported codec version: 2"),
                Arguments.of(
                        missingVersion,
                        UnsupportedCodecMetadataException.class,
                        "Missing required codec metadata key: v"),
                Arguments.of(
                        noOpMetadata,
                        UnsupportedCodecMetadataException.class,
                        "Codec metadata must enable compression, encoding or checksum"),
                Arguments.of(
                        invalidFormat,
                        UnsupportedCodecMetadataException.class,
                        "Unsupported codec metadata: v=1;c"),
                Arguments.of(
                        duplicateKey,
                        UnsupportedCodecMetadataException.class,
                        "Duplicate codec metadata key: c"));
    }

    private static String payloadWithEqualEncodedLengthForGzip() {
        Codec codec = new Codec(CompressionAlgorithm.GZIP);
        for (int length = 1; length <= 4096; length++) {
            String payload = "a".repeat(length);
            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            if (codec.encode(payloadBytes).length == payloadBytes.length) {
                return payload;
            }
        }
        throw new AssertionError("Expected to find a GZIP payload with equal encoded length within 4096 bytes");
    }

    private static Map<String, MessageAttributeValue> codecAttributes(
            byte[] payloadBytes,
            CompressionAlgorithm compressionAlgorithm,
            ChecksumAlgorithm checksumAlgorithm) {
        if (compressionAlgorithm == CompressionAlgorithm.NONE
                && checksumAlgorithm == ChecksumAlgorithm.NONE) {
            return Map.of();
        }
        CodecConfiguration configuration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                compressionAlgorithm,
                compressionAlgorithm == CompressionAlgorithm.NONE
                        ? EncodingAlgorithm.NONE
                        : EncodingAlgorithm.BASE64,
                checksumAlgorithm);
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        CodecMetadataAttributeHandler.forOutbound(configuration, payloadBytes, true)
                .applyTo(attributes);
        return attributes;
    }

    private static String encodedPlainPayload(String payload) {
        return Base64PayloadCodec.instance().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
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
