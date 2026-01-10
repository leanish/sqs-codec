/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

class SqsPayloadCodecInterceptorTest {

    private final PayloadCodec payloadCodec = PayloadCodecs.forEncoding(PayloadEncoding.ZSTD_BASE64);
    private final SqsPayloadCodecInterceptor interceptor = new SqsPayloadCodecInterceptor();

    @Test
    void modifyRequestEncodesMessageBody() {
        String payload = "{\"value\":42}";
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(payload)
                .messageAttributes(Map.of("shopId", stringAttribute("shop-1")))
                .build();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(SendMessageRequest.class);
        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageAttributes())
                .containsKeys(
                        PayloadCodecAttributes.ENCODING,
                        PayloadCodecAttributes.CHECKSUM_ALG,
                        PayloadCodecAttributes.CHECKSUM,
                        PayloadCodecAttributes.VERSION,
                        PayloadCodecAttributes.RAW_LENGTH);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.ENCODING).stringValue())
                .isEqualTo(PayloadEncoding.ZSTD_BASE64.attributeValue());
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CHECKSUM_ALG).stringValue())
                .isEqualTo(PayloadCodecAttributes.CHECKSUM_SHA256);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.VERSION).stringValue())
                .isEqualTo(PayloadCodecAttributes.VERSION_VALUE);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.RAW_LENGTH).stringValue())
                .isEqualTo(Integer.toString(payload.getBytes(StandardCharsets.UTF_8).length));
        String decoded = payloadCodec.decodeToString(encoded.messageBody());
        assertThat(decoded).isEqualTo(payload);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CHECKSUM).stringValue())
                .isEqualTo(sha256Base64(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void modifyRequestAllowsPlaintextEncoding() {
        String payload = "{\"value\":42}";
        SqsPayloadCodecInterceptor plaintext = new SqsPayloadCodecInterceptor(PayloadEncoding.NONE);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(payload)
                .messageAttributes(Map.of("shopId", stringAttribute("shop-1")))
                .build();

        SdkRequest modified = plaintext.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(SendMessageRequest.class);
        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageBody()).isEqualTo(payload);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.ENCODING).stringValue())
                .isEqualTo(PayloadEncoding.NONE.attributeValue());
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.CHECKSUM).stringValue())
                .isEqualTo(sha256Base64(payload.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void modifyRequestUsesProvidedPayloadCodec() {
        String payload = "{\"value\":42}";
        PayloadCodec codec = PayloadCodecs.forEncoding(PayloadEncoding.NONE);
        SqsPayloadCodecInterceptor plaintext = new SqsPayloadCodecInterceptor(codec);
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(payload)
                .messageAttributes(Map.of("shopId", stringAttribute("shop-1")))
                .build();

        SdkRequest modified = plaintext.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(SendMessageRequest.class);
        SendMessageRequest encoded = (SendMessageRequest) modified;
        assertThat(encoded.messageBody()).isEqualTo(payload);
        assertThat(encoded.messageAttributes().get(PayloadCodecAttributes.ENCODING).stringValue())
                .isEqualTo(PayloadEncoding.NONE.attributeValue());
    }

    @Test
    void modifyRequestSkipsWhenEncodingAttributePresent() {
        String payload = "raw";
        SendMessageRequest request = SendMessageRequest.builder()
                .messageBody(payload)
                .messageAttributes(Map.of(
                        PayloadCodecAttributes.ENCODING,
                        stringAttribute(PayloadEncoding.ZSTD_BASE64.attributeValue())))
                .build();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isSameAs(request);
    }

    @Test
    void modifyRequestEncodesBatchMessages() {
        String payload = "{\"value\":42}";
        String skippedPayload = "{\"value\":43}";
        SendMessageBatchRequest request = SendMessageBatchRequest.builder()
                .entries(
                        SendMessageBatchRequestEntry.builder()
                                .id("encoded")
                                .messageBody(payload)
                                .messageAttributes(Map.of("shopId", stringAttribute("shop-1")))
                                .build(),
                        SendMessageBatchRequestEntry.builder()
                                .id("skipped")
                                .messageBody(skippedPayload)
                                .messageAttributes(Map.of(
                                        PayloadCodecAttributes.ENCODING,
                                        stringAttribute(PayloadEncoding.ZSTD_BASE64.attributeValue())))
                                .build())
                .build();

        SdkRequest modified = interceptor.modifyRequest(
                new ModifyRequestContext(request),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(SendMessageBatchRequest.class);
        SendMessageBatchRequest encoded = (SendMessageBatchRequest) modified;
        SendMessageBatchRequestEntry encodedEntry = encoded.entries()
                .stream()
                .filter(entry -> "encoded".equals(entry.id()))
                .findFirst()
                .orElseThrow();
        assertThat(encodedEntry.messageAttributes())
                .containsKeys(
                        PayloadCodecAttributes.ENCODING,
                        PayloadCodecAttributes.CHECKSUM_ALG,
                        PayloadCodecAttributes.CHECKSUM,
                        PayloadCodecAttributes.VERSION,
                        PayloadCodecAttributes.RAW_LENGTH);
        assertThat(encodedEntry.messageAttributes().get(PayloadCodecAttributes.ENCODING).stringValue())
                .isEqualTo(PayloadEncoding.ZSTD_BASE64.attributeValue());
        assertThat(encodedEntry.messageAttributes().get(PayloadCodecAttributes.CHECKSUM_ALG).stringValue())
                .isEqualTo(PayloadCodecAttributes.CHECKSUM_SHA256);
        assertThat(encodedEntry.messageAttributes().get(PayloadCodecAttributes.VERSION).stringValue())
                .isEqualTo(PayloadCodecAttributes.VERSION_VALUE);
        assertThat(encodedEntry.messageAttributes().get(PayloadCodecAttributes.RAW_LENGTH).stringValue())
                .isEqualTo(Integer.toString(payload.getBytes(StandardCharsets.UTF_8).length));
        assertThat(encodedEntry.messageAttributes().get(PayloadCodecAttributes.CHECKSUM).stringValue())
                .isEqualTo(sha256Base64(payload.getBytes(StandardCharsets.UTF_8)));
        assertThat(encodedEntry.messageAttributes().get("shopId").stringValue())
                .isEqualTo("shop-1");
        String decoded = payloadCodec.decodeToString(encodedEntry.messageBody());
        assertThat(decoded).isEqualTo(payload);

        SendMessageBatchRequestEntry skippedEntry = encoded.entries()
                .stream()
                .filter(entry -> "skipped".equals(entry.id()))
                .findFirst()
                .orElseThrow();
        assertThat(skippedEntry.messageBody()).isEqualTo(skippedPayload);
        assertThat(skippedEntry.messageAttributes())
                .containsOnlyKeys(PayloadCodecAttributes.ENCODING);
    }

    @Test
    void modifyResponseDecodesMessageBody() {
        String payload = "{\"value\":42}";
        String encodedBody = payloadCodec.encode(payload);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(messageWithAttributes(
                        encodedBody,
                        payload.getBytes(StandardCharsets.UTF_8),
                        PayloadEncoding.ZSTD_BASE64))
                .build();

        SdkResponse modified = interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(ReceiveMessageResponse.class);
        ReceiveMessageResponse decoded = (ReceiveMessageResponse) modified;
        assertThat(decoded.messages()).hasSize(1);
        assertThat(decoded.messages().getFirst().body()).isEqualTo(payload);
    }

    @Test
    void modifyResponseThrowsOnChecksumMismatch() {
        String payload = "{\"value\":42}";
        String encodedBody = payloadCodec.encode(payload);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(messageWithAttributes(
                        encodedBody,
                        "bad".getBytes(StandardCharsets.UTF_8),
                        PayloadEncoding.ZSTD_BASE64))
                .build();

        assertThatThrownBy(() -> interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Payload checksum mismatch");
    }

    @Test
    void modifyResponseHandlesPlaintextEncoding() {
        String payload = "{\"value\":42}";
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(messageWithAttributes(
                        payload,
                        payload.getBytes(StandardCharsets.UTF_8),
                        PayloadEncoding.NONE))
                .build();

        SdkResponse modified = interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(ReceiveMessageResponse.class);
        ReceiveMessageResponse decoded = (ReceiveMessageResponse) modified;
        assertThat(decoded.messages().getFirst().body()).isEqualTo(payload);
    }

    @Test
    void modifyResponsePassesThroughWhenEncodingMissing() {
        String payload = "{\"value\":42}";
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(payload)
                        .build())
                .build();

        SdkResponse modified = interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes());

        assertThat(modified).isInstanceOf(ReceiveMessageResponse.class);
        ReceiveMessageResponse decoded = (ReceiveMessageResponse) modified;
        assertThat(decoded.messages().getFirst().body()).isEqualTo(payload);
        assertThat(decoded.messages().getFirst().messageAttributes()).isEmpty();
    }

    @Test
    void modifyResponseThrowsWhenChecksumIsMissing() {
        String payload = "{\"value\":42}";
        String encodedBody = payloadCodec.encode(payload);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payload.getBytes(StandardCharsets.UTF_8),
                PayloadEncoding.ZSTD_BASE64);
        attributes.remove(PayloadCodecAttributes.CHECKSUM);
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Missing required SQS attribute: " + PayloadCodecAttributes.CHECKSUM);
    }

    @Test
    void modifyResponseThrowsWhenEncodingIsUnsupported() {
        String payload = "{\"value\":42}";
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payload.getBytes(StandardCharsets.UTF_8),
                PayloadEncoding.ZSTD_BASE64);
        attributes.put(PayloadCodecAttributes.ENCODING, stringAttribute("snappy+base64"));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(payload)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Unsupported payload encoding: snappy+base64");
    }

    @Test
    void modifyResponseThrowsWhenChecksumAlgorithmIsUnsupported() {
        String payload = "{\"value\":42}";
        String encodedBody = payloadCodec.encode(payload);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payload.getBytes(StandardCharsets.UTF_8),
                PayloadEncoding.ZSTD_BASE64);
        attributes.put(PayloadCodecAttributes.CHECKSUM_ALG, stringAttribute("sha1"));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Unsupported checksum algorithm: sha1");
    }

    @Test
    void modifyResponseThrowsWhenVersionIsUnsupported() {
        String payload = "{\"value\":42}";
        String encodedBody = payloadCodec.encode(payload);
        Map<String, MessageAttributeValue> attributes = codecAttributes(
                payload.getBytes(StandardCharsets.UTF_8),
                PayloadEncoding.ZSTD_BASE64);
        attributes.put(PayloadCodecAttributes.VERSION, stringAttribute("2.0.0"));
        ReceiveMessageResponse response = ReceiveMessageResponse.builder()
                .messages(Message.builder()
                        .body(encodedBody)
                        .messageAttributes(attributes)
                        .build())
                .build();

        assertThatThrownBy(() -> interceptor.modifyResponse(
                new ModifyResponseContext(response),
                new ExecutionAttributes()))
                .isInstanceOf(PayloadCodecException.class)
                .hasMessage("Unsupported codec version: 2.0.0");
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder()
                .dataType("String")
                .stringValue(value)
                .build();
    }

    private static Message messageWithAttributes(
            String body,
            byte[] payloadBytes,
            PayloadEncoding encoding) {
        return Message.builder()
                .body(body)
                .messageAttributes(codecAttributes(payloadBytes, encoding))
                .build();
    }

    private static Map<String, MessageAttributeValue> codecAttributes(
            byte[] payloadBytes,
            PayloadEncoding encoding) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        attributes.put(PayloadCodecAttributes.ENCODING, stringAttribute(encoding.attributeValue()));
        attributes.put(PayloadCodecAttributes.CHECKSUM_ALG, stringAttribute(PayloadCodecAttributes.CHECKSUM_SHA256));
        attributes.put(PayloadCodecAttributes.CHECKSUM, stringAttribute(sha256Base64(payloadBytes)));
        attributes.put(PayloadCodecAttributes.VERSION, stringAttribute(PayloadCodecAttributes.VERSION_VALUE));
        attributes.put(PayloadCodecAttributes.RAW_LENGTH, MessageAttributeValue.builder()
                .dataType("Number")
                .stringValue(Integer.toString(payloadBytes.length))
                .build());
        return attributes;
    }

    private static String sha256Base64(byte[] payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().encodeToString(digest.digest(payload));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class ModifyRequestContext implements Context.ModifyRequest {
        private final SdkRequest request;

        private ModifyRequestContext(SdkRequest request) {
            this.request = request;
        }

        @Override
        public SdkRequest request() {
            return request;
        }
    }

    private static final class ModifyResponseContext implements Context.ModifyResponse {
        private final SdkResponse response;

        private ModifyResponseContext(SdkResponse response) {
            this.response = response;
        }

        @Override
        public SdkRequest request() {
            return null;
        }

        @Override
        public software.amazon.awssdk.http.SdkHttpRequest httpRequest() {
            return null;
        }

        @Override
        public Optional<software.amazon.awssdk.core.sync.RequestBody> requestBody() {
            return Optional.empty();
        }

        @Override
        public Optional<software.amazon.awssdk.core.async.AsyncRequestBody> asyncRequestBody() {
            return Optional.empty();
        }

        @Override
        public software.amazon.awssdk.http.SdkHttpResponse httpResponse() {
            return null;
        }

        @Override
        public Optional<org.reactivestreams.Publisher<java.nio.ByteBuffer>> responsePublisher() {
            return Optional.empty();
        }

        @Override
        public Optional<java.io.InputStream> responseBody() {
            return Optional.empty();
        }

        @Override
        public SdkResponse response() {
            return response;
        }
    }
}
