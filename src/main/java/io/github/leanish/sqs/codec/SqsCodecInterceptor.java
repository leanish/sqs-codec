/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionLevel;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import io.github.leanish.sqs.codec.algorithms.compression.CompressionException;
import io.github.leanish.sqs.codec.algorithms.compression.DecompressedPayloadTooLargeException;
import io.github.leanish.sqs.codec.algorithms.encoding.InvalidPayloadException;
import io.github.leanish.sqs.codec.attributes.ChecksumValidationException;
import io.github.leanish.sqs.codec.attributes.CodecAttributes;
import io.github.leanish.sqs.codec.attributes.CodecMetadataAttributeHandler;
import io.github.leanish.sqs.codec.attributes.UnsupportedCodecMetadataException;
import lombok.AccessLevel;
import lombok.With;
import software.amazon.awssdk.core.SdkRequest;
import software.amazon.awssdk.core.SdkResponse;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * AWS SDK v2 execution interceptor that compresses, encodes, decodes and validates SQS message
 * bodies while managing codec metadata.
 *
 * <p>When compression is enabled and encoding is left at {@link EncodingAlgorithm#NONE}, the
 * interceptor uses URL-safe Base64 as the effective payload encoding.
 */
@With
public class SqsCodecInterceptor implements ExecutionInterceptor {

    private static final int MAX_SQS_MESSAGE_ATTRIBUTES = 10;
    /** The default limit on a decoded payload: 16 MiB. */
    public static final int DEFAULT_MAX_DECODED_PAYLOAD_BYTES = 16 * 1024 * 1024;
    // The base defaultInterceptor(...) copies; its version is always replaced by the caller's, never returned as is.
    private static final SqsCodecInterceptor DEFAULT = new SqsCodecInterceptor(
            CompressionAlgorithm.NONE,
            null,
            EncodingAlgorithm.NONE,
            ChecksumAlgorithm.MD5,
            /* skipCompressionWhenLarger= */ true,
            DEFAULT_MAX_DECODED_PAYLOAD_BYTES,
            MetadataVersion.V2);

    private final CompressionAlgorithm compressionAlgorithm;
    private final @Nullable CompressionLevel compressionLevel;
    private final EncodingAlgorithm encodingAlgorithm;
    private final ChecksumAlgorithm checksumAlgorithm;
    private final boolean skipCompressionWhenLarger;
    // The most bytes a payload may decode to, wherever this interceptor decodes one (received messages, and pre-encoded
    // messages it validates before sending). A compressed payload must decode to exactly its declared raw length (l),
    // which may not exceed this limit.
    private final int maxDecodedPayloadBytes;
    // The metadata version fresh sends write; receiving reads every supported version regardless.
    @With(AccessLevel.NONE)
    private final MetadataVersion outboundMetadataVersion;

    private SqsCodecInterceptor(
            CompressionAlgorithm compressionAlgorithm,
            @Nullable CompressionLevel compressionLevel,
            EncodingAlgorithm encodingAlgorithm,
            ChecksumAlgorithm checksumAlgorithm,
            boolean skipCompressionWhenLarger,
            int maxDecodedPayloadBytes,
            MetadataVersion outboundMetadataVersion) {
        this.compressionAlgorithm = Objects.requireNonNull(compressionAlgorithm, "compressionAlgorithm");
        this.compressionLevel = compressionLevel;
        this.encodingAlgorithm = Objects.requireNonNull(encodingAlgorithm, "encodingAlgorithm");
        this.checksumAlgorithm = Objects.requireNonNull(checksumAlgorithm, "checksumAlgorithm");
        this.skipCompressionWhenLarger = skipCompressionWhenLarger;
        if (maxDecodedPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxDecodedPayloadBytes must be positive: " + maxDecodedPayloadBytes);
        }
        this.maxDecodedPayloadBytes = maxDecodedPayloadBytes;
        this.outboundMetadataVersion = Objects.requireNonNull(outboundMetadataVersion, "outboundMetadataVersion");
        this.compressionAlgorithm.validateCompressionLevel(compressionLevel);
    }

    /**
     * An interceptor with the default settings (no compression, no encoding, MD5 checksum) that writes the given
     * metadata version. There's no default version: {@link MetadataVersion#V1} while any consumer of the client's queues
     * still runs 0.5.x, {@link MetadataVersion#V2} otherwise (see the README's "Upgrading from 0.5.x").
     *
     * @param outboundMetadataVersion the version fresh sends write
     * @return the default interceptor writing that version
     */
    public static SqsCodecInterceptor defaultInterceptor(MetadataVersion outboundMetadataVersion) {
        return DEFAULT.withOutboundMetadataVersion(outboundMetadataVersion);
    }

    /**
     * Clears any configured compression level so the selected compression algorithm uses its
     * built-in default again.
     */
    public SqsCodecInterceptor withoutCompressionLevel() {
        return withCompressionLevel(null);
    }

    /**
     * The metadata version fresh sends write, as chosen in {@link #defaultInterceptor(MetadataVersion)}; this switches it
     * on an existing interceptor. {@link MetadataVersion#V1} is for upgrading from 0.5.x while any consumer of the
     * client's queues still runs it; the README's "Upgrading from 0.5.x" describes the order. Receiving is unaffected:
     * every supported version is read either way.
     *
     * <p>A pre-encoded message, sent unchanged, is refused with {@link UnsupportedCodecMetadataException} when its
     * otherwise valid metadata carries a newer version than this one, and so is the whole batch it's in.
     *
     * @param outboundMetadataVersion the version to write
     * @return a copy of this interceptor writing that version
     */
    public SqsCodecInterceptor withOutboundMetadataVersion(MetadataVersion outboundMetadataVersion) {
        return new SqsCodecInterceptor(
                compressionAlgorithm,
                compressionLevel,
                encodingAlgorithm,
                checksumAlgorithm,
                skipCompressionWhenLarger,
                maxDecodedPayloadBytes,
                outboundMetadataVersion);
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
        if (request instanceof ReceiveMessageRequest receiveMessageRequest) {
            return ensureCodecAttributesRequested(receiveMessageRequest);
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

    @SuppressWarnings("DuplicatedCode") // known but sadly SendMessageRequest and SendMessageBatchRequestEntry are not polymorphic
    private SendMessageRequest encodeSendMessage(SendMessageRequest request) {
        if (CodecMetadataAttributeHandler.hasCodecAttributes(request.messageAttributes())) {
            // Already codec-processed upstream; avoid double-processing or overwriting attributes (if valid)
            validateOutboundAttributeCount(request.messageAttributes());
            validateOutboundPreEncodedPayload(request.messageBody(), request.messageAttributes());
            return request;
        }

        EncodedMessage encoded = encode(request.messageBody(), request.messageAttributes());

        return request.toBuilder()
                .messageBody(encoded.body)
                .messageAttributes(encoded.attributes)
                .build();
    }

    private SendMessageBatchRequest encodeSendMessageBatch(SendMessageBatchRequest request) {
        List<SendMessageBatchRequestEntry> encodedEntries = request.entries()
                .stream()
                .map(this::encodeSendMessageEntry)
                .toList();

        return request.toBuilder()
                .entries(encodedEntries)
                .build();
    }

    @SuppressWarnings("DuplicatedCode") // known but sadly SendMessageRequest and SendMessageBatchRequestEntry are not polymorphic
    private SendMessageBatchRequestEntry encodeSendMessageEntry(SendMessageBatchRequestEntry entry) {
        if (CodecMetadataAttributeHandler.hasCodecAttributes(entry.messageAttributes())) {
            // Already codec-processed upstream; avoid double-processing or overwriting attributes (if valid)
            validateOutboundAttributeCount(entry.messageAttributes());
            validateOutboundPreEncodedPayload(entry.messageBody(), entry.messageAttributes());
            return entry;
        }

        EncodedMessage encoded = encode(entry.messageBody(), entry.messageAttributes());

        return entry.toBuilder()
                .messageBody(encoded.body)
                .messageAttributes(encoded.attributes)
                .build();
    }

    private EncodedMessage encode(String originalBody, Map<String, MessageAttributeValue> originalAttributes) {
        byte[] payloadBytes = originalBody.getBytes(StandardCharsets.UTF_8);
        EncodedPayload encodedPayload = encodeOutboundPayload(payloadBytes, configuration());
        Map<String, MessageAttributeValue> attributes = outboundAttributes(
                originalAttributes,
                encodedPayload.configuration(),
                payloadBytes);
        return new EncodedMessage(encodedPayload.body(), attributes);
    }

    private void validateOutboundPreEncodedPayload(String messageBody, Map<String, MessageAttributeValue> attributes) {
        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(attributes);
        CodecConfiguration configuration = metadata.configuration();
        if (configuration.version() > outboundMetadataVersion.value()) {
            // Writing an older version means older consumers remain, and they'd refuse it and fail their whole receive.
            throw UnsupportedCodecMetadataException.preEncodedVersionNewerThanOutbound(
                    configuration.version(), outboundMetadataVersion.value());
        }
        byte[] payloadBytes = decodePayloadIfNeeded(messageBody, metadata);
        if (shouldValidateChecksum(configuration)) {
            validateChecksum(configuration, requiredChecksumValue(metadata), payloadBytes);
        }
        if (shouldDecode(configuration)) {
            // Refused here as on receive, rather than sending a message this version's receivers refuse.
            requireUtf8(payloadBytes);
        }
    }

    private ReceiveMessageRequest ensureCodecAttributesRequested(ReceiveMessageRequest request) {
        Set<String> attributeNames = new HashSet<>(request.messageAttributeNames());
        if (attributeNames.contains("All") || attributeNames.contains(CodecAttributes.META)) {
            return request;
        }

        attributeNames.add(CodecAttributes.META);
        return request.toBuilder()
                .messageAttributeNames(attributeNames)
                .build();
    }

    private ReceiveMessageResponse decodeReceiveMessageResponse(ReceiveMessageResponse response) {
        List<Message> messages = response.messages();
        if (messages.isEmpty()) {
            return response;
        }

        List<Message> decoded = messages.stream()
                .map(this::decodeMessageIfNeeded)
                .toList();

        return response.toBuilder()
                .messages(decoded)
                .build();
    }

    private Message decodeMessageIfNeeded(Message message) {
        Map<String, MessageAttributeValue> attributes = message.messageAttributes();
        if (!CodecMetadataAttributeHandler.hasCodecAttributes(attributes)) {
            // allowing messages queued before this codec was added
            return message;
        }

        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(attributes);
        CodecConfiguration configuration = metadata.configuration();
        boolean shouldDecode = shouldDecode(configuration);
        boolean shouldValidateChecksum = shouldValidateChecksum(configuration);

        byte[] payloadBytes = decodePayloadIfNeeded(message.body(), metadata);
        if (shouldValidateChecksum) {
            validateChecksum(configuration, requiredChecksumValue(metadata), payloadBytes);
        }
        if (!shouldDecode) {
            return message;
        }

        return message.toBuilder()
                .body(requireUtf8(payloadBytes))
                .build();
    }

    /** The decoded payload as text: one that isn't valid UTF-8 is refused rather than altered. */
    private static String requireUtf8(byte[] payloadBytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payloadBytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new InvalidPayloadException("Decoded payload is not valid UTF-8", e);
        }
    }

    private byte[] decodePayloadIfNeeded(String messageBody, CodecMetadataAttributeHandler metadata) {
        CodecConfiguration configuration = metadata.configuration();
        byte[] messageBodyBytes = messageBody.getBytes(StandardCharsets.UTF_8);
        if (!shouldDecode(configuration)) {
            return messageBodyBytes;
        }
        Codec codec = new Codec(
                configuration.compressionAlgorithm(),
                configuration.encodingAlgorithm());
        CompressionAlgorithm compression = configuration.compressionAlgorithm();
        if (compression == CompressionAlgorithm.NONE) {
            return codec.decode(messageBodyBytes, maxDecodedPayloadBytes);
        }
        if (configuration.version() == CodecAttributes.LEGACY_VERSION_VALUE) {
            return decodeLegacy(codec, messageBodyBytes, metadata);
        }
        // A compressed v=2 payload decodes to exactly its declared raw length, so that length bounds the decompression.
        int expectedLength = metadata.rawLength()
                .orElseThrow(() -> new IllegalStateException("A compressed v=2 payload always declares its raw length"));
        if (expectedLength > maxDecodedPayloadBytes) {
            throw DecompressedPayloadTooLargeException.declaredOverLimit(
                    compression.id(), expectedLength, maxDecodedPayloadBytes);
        }
        byte[] decoded = codec.decode(messageBodyBytes, expectedLength);
        if (decoded.length != expectedLength) {
            throw CompressionException.lengthMismatch(compression.id(), expectedLength, decoded.length);
        }
        return decoded;
    }

    /**
     * A compressed v=1 payload (0.5.x, read until 0.7.0): its raw length is optional, and its zstd frames were streamed
     * with the level's window, so the configured limit bounds the decompression; a raw length it declares must match.
     */
    private byte[] decodeLegacy(Codec codec, byte[] messageBodyBytes, CodecMetadataAttributeHandler metadata) {
        byte[] decoded = codec.decode(messageBodyBytes, maxDecodedPayloadBytes);
        OptionalInt declaredLength = metadata.rawLength();
        if (declaredLength.isPresent() && decoded.length != declaredLength.getAsInt()) {
            throw CompressionException.lengthMismatch(
                    metadata.configuration().compressionAlgorithm().id(), declaredLength.getAsInt(), decoded.length);
        }
        return decoded;
    }

    private boolean shouldDecode(CodecConfiguration configuration) {
        return configuration.compressionAlgorithm() != CompressionAlgorithm.NONE
                || configuration.encodingAlgorithm() != EncodingAlgorithm.NONE;
    }

    private boolean shouldValidateChecksum(CodecConfiguration configuration) {
        return configuration.checksumAlgorithm() != ChecksumAlgorithm.NONE;
    }

    private boolean shouldWriteMetadata(CodecConfiguration configuration) {
        return shouldDecode(configuration)
                || shouldValidateChecksum(configuration);
    }

    private void validateChecksum(
            CodecConfiguration configuration,
            String checksumValue,
            byte[] payloadBytes) {
        String actualChecksum = configuration.checksumAlgorithm()
                .implementation()
                .checksum(payloadBytes);
        if (!actualChecksum.equals(checksumValue)) {
            throw ChecksumValidationException.mismatch();
        }
    }

    private String requiredChecksumValue(CodecMetadataAttributeHandler metadata) {
        return Objects.requireNonNull(
                metadata.checksumValue(),
                "Invariant violation: checksum metadata value must be present when checksum validation is enabled");
    }

    private CodecConfiguration configuration() {
        return new CodecConfiguration(
                outboundMetadataVersion.value(),
                compressionAlgorithm,
                encodingAlgorithm,
                checksumAlgorithm);
    }

    private Map<String, MessageAttributeValue> outboundAttributes(
            Map<String, MessageAttributeValue> originalAttributes,
            CodecConfiguration configuration,
            byte[] payloadBytes) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>(originalAttributes);
        if (shouldWriteMetadata(configuration)) {
            CodecMetadataAttributeHandler.forOutbound(configuration, payloadBytes).applyTo(attributes);
        }
        validateOutboundAttributeCount(attributes);
        return attributes;
    }

    private EncodedPayload encodeOutboundPayload(byte[] payloadBytes, CodecConfiguration configuredConfiguration) {
        Codec codec = new Codec(
                configuredConfiguration.compressionAlgorithm(),
                compressionLevel,
                configuredConfiguration.encodingAlgorithm());
        byte[] encodedBytes = codec.encode(payloadBytes);
        if (!shouldSkipCompression(payloadBytes, encodedBytes, configuredConfiguration)) {
            return new EncodedPayload(
                    configuredConfiguration,
                    new String(encodedBytes, StandardCharsets.UTF_8));
        }

        CodecConfiguration uncompressedConfiguration = new CodecConfiguration(
                configuredConfiguration.version(),
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                configuredConfiguration.checksumAlgorithm());
        return new EncodedPayload(
                uncompressedConfiguration,
                new String(payloadBytes, StandardCharsets.UTF_8));
    }

    private boolean shouldSkipCompression(
            byte[] payloadBytes,
            byte[] encodedBytes,
            CodecConfiguration configuredConfiguration) {
        return skipCompressionWhenLarger
                && configuredConfiguration.compressionAlgorithm() != CompressionAlgorithm.NONE
                && encodedBytes.length >= payloadBytes.length;
    }

    private void validateOutboundAttributeCount(Map<String, MessageAttributeValue> attributes) {
        int attributeCount = attributes.size();
        if (attributeCount <= MAX_SQS_MESSAGE_ATTRIBUTES) {
            return;
        }

        throw new CodecException(
                "SQS supports at most " + MAX_SQS_MESSAGE_ATTRIBUTES
                        + " message attributes, but request has " + attributeCount
                        + "; reduce custom attributes");
    }

    private record EncodedMessage(String body, Map<String, MessageAttributeValue> attributes) {
    }

    private record EncodedPayload(CodecConfiguration configuration, String body) {
    }
}
