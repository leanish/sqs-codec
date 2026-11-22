/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
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
    private static final int UTF8_CHECK_BUFFER_CHARS = 4096;
    /** The default limit on a decoded payload: 16 MiB. */
    public static final int DEFAULT_MAX_DECODED_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final SqsCodecInterceptor DEFAULT = new SqsCodecInterceptor(
            CompressionAlgorithm.NONE,
            null,
            EncodingAlgorithm.NONE,
            ChecksumAlgorithm.MD5,
            /* skipCompressionWhenLarger= */ true,
            DEFAULT_MAX_DECODED_PAYLOAD_BYTES);

    private final CompressionAlgorithm compressionAlgorithm;
    private final @Nullable CompressionLevel compressionLevel;
    private final EncodingAlgorithm encodingAlgorithm;
    private final ChecksumAlgorithm checksumAlgorithm;
    private final boolean skipCompressionWhenLarger;
    // The most bytes a payload may decode to, wherever this interceptor decodes one (received messages, and pre-encoded
    // messages it validates before sending), and the most a fresh payload it compresses or encodes may have. A
    // compressed payload that declares a usable raw length (l) must decode to exactly that length.
    private final int maxDecodedPayloadBytes;

    private SqsCodecInterceptor(
            CompressionAlgorithm compressionAlgorithm,
            @Nullable CompressionLevel compressionLevel,
            EncodingAlgorithm encodingAlgorithm,
            ChecksumAlgorithm checksumAlgorithm,
            boolean skipCompressionWhenLarger,
            int maxDecodedPayloadBytes) {
        this.compressionAlgorithm = Objects.requireNonNull(compressionAlgorithm, "compressionAlgorithm");
        this.compressionLevel = compressionLevel;
        this.encodingAlgorithm = Objects.requireNonNull(encodingAlgorithm, "encodingAlgorithm");
        this.checksumAlgorithm = Objects.requireNonNull(checksumAlgorithm, "checksumAlgorithm");
        this.skipCompressionWhenLarger = skipCompressionWhenLarger;
        if (maxDecodedPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxDecodedPayloadBytes must be positive: " + maxDecodedPayloadBytes);
        }
        this.maxDecodedPayloadBytes = maxDecodedPayloadBytes;
        this.compressionAlgorithm.validateCompressionLevel(compressionLevel);
    }

    /**
     * An interceptor with the default settings: no compression, no encoding, MD5 checksum, and a
     * {@value #DEFAULT_MAX_DECODED_PAYLOAD_BYTES}-byte limit on decoded payloads.
     *
     * @return the default interceptor
     */
    public static SqsCodecInterceptor defaultInterceptor() {
        return DEFAULT;
    }

    /**
     * Clears any configured compression level so the selected compression algorithm uses its
     * built-in default again.
     */
    public SqsCodecInterceptor withoutCompressionLevel() {
        return withCompressionLevel(null);
    }

    /**
     * Sets the most bytes a payload may decode to, wherever this interceptor decodes one: received messages, and already
     * encoded messages it validates before sending. When it's configured to compress or encode, a fresh payload whose
     * UTF-8 bytes exceed it is refused before sending, even one it would have sent uncompressed. The default is
     * {@value #DEFAULT_MAX_DECODED_PAYLOAD_BYTES}. It bounds each payload's decoded bytes, not memory.
     *
     * @param maxDecodedPayloadBytes the limit, in bytes; it must be positive
     * @return this interceptor if the limit is unchanged, otherwise a new interceptor with the limit and the same other
     *         settings
     * @throws IllegalArgumentException when {@code maxDecodedPayloadBytes} isn't positive
     */
    // Written out, rather than generated by @With, only so the published Javadoc documents it; same behavior.
    public SqsCodecInterceptor withMaxDecodedPayloadBytes(int maxDecodedPayloadBytes) {
        if (this.maxDecodedPayloadBytes == maxDecodedPayloadBytes) {
            return this;
        }
        return new SqsCodecInterceptor(
                compressionAlgorithm,
                compressionLevel,
                encodingAlgorithm,
                checksumAlgorithm,
                skipCompressionWhenLarger,
                maxDecodedPayloadBytes);
    }

    /**
     * Has no effect: fresh sends that compress always write the raw payload length ({@code l}), which 0.7.0 will
     * require. Kept so code built against 0.5.x keeps working; in 0.5.x, {@code false} omitted {@code l}.
     *
     * @param includeRawPayloadLength ignored
     * @return this interceptor, unchanged
     * @deprecated {@code l} is written whenever a send compresses; remove the call
     */
    @Deprecated(since = "0.6.0")
    // Not @InlineMe: inlining to "this" would drop the evaluation of the caller's argument expression.
    @SuppressWarnings("InlineMeSuggester")
    public SqsCodecInterceptor withIncludeRawPayloadLength(boolean includeRawPayloadLength) {
        return this;
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
        CodecConfiguration configuration = configuration();
        boolean limited = shouldDecode(configuration);
        // Refused before compressing or encoding, rather than sending a message that receivers with this limit refuse,
        // failing their whole receive. Checked on the configured codec, before skipCompressionWhenLarger could fall back
        // to sending it as is, so the rule doesn't depend on how well the payload compresses. Every UTF-16 code unit
        // takes at least one UTF-8 byte, so a body with more code units than the limit is refused before converting it.
        if (limited && originalBody.length() > maxDecodedPayloadBytes) {
            throw DecompressedPayloadTooLargeException.outboundCodeUnitsOverLimit(
                    originalBody.length(),
                    maxDecodedPayloadBytes);
        }
        byte[] payloadBytes = originalBody.getBytes(StandardCharsets.UTF_8);
        if (limited && payloadBytes.length > maxDecodedPayloadBytes) {
            throw DecompressedPayloadTooLargeException.outboundOverLimit(payloadBytes.length, maxDecodedPayloadBytes);
        }
        EncodedPayload encodedPayload = encodeOutboundPayload(payloadBytes, configuration);
        Map<String, MessageAttributeValue> attributes = outboundAttributes(
                originalAttributes,
                encodedPayload.configuration(),
                payloadBytes);
        return new EncodedMessage(encodedPayload.body(), attributes);
    }

    private void validateOutboundPreEncodedPayload(String messageBody, Map<String, MessageAttributeValue> attributes) {
        CodecMetadataAttributeHandler metadata = CodecMetadataAttributeHandler.fromAttributes(attributes);
        CodecConfiguration configuration = metadata.configuration();
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

        requireUtf8(payloadBytes);
        return message.toBuilder()
                .body(new String(payloadBytes, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Refuses a decoded payload that isn't valid UTF-8 rather than letting the {@code String} conversion alter it.
     *
     * <p>Decodes into a small reused buffer and discards the characters: decoding the whole payload at once would
     * allocate a {@code char[]} as long as the payload, while {@code new String(bytes, UTF_8)} afterwards can use
     * one byte per character for ASCII and Latin-1 text, with OpenJDK's compact strings enabled (the default).
     */
    private static void requireUtf8(byte[] payloadBytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(payloadBytes);
        CharBuffer scratch = CharBuffer.allocate(UTF8_CHECK_BUFFER_CHARS);
        try {
            CoderResult result;
            do {
                scratch.clear();
                result = decoder.decode(input, scratch, true);
                if (result.isError()) {
                    result.throwException();
                }
            } while (result.isOverflow());
            // No flush: with endOfInput, a truncated last character is already reported as malformed, and the UTF-8
            // decoder keeps nothing else to flush; the decoder isn't reused.
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
        // The raw length (l) is sender-controlled, and 0.5.x writers may omit it, so the configured limit, not l, bounds
        // the decompression (and zstd's window: 0.5.x's streamed frames can declare the level's window).
        byte[] decoded = codec.decode(messageBodyBytes, maxDecodedPayloadBytes);
        OptionalInt declaredLength = metadata.rawLength();
        if (declaredLength.isPresent() && decoded.length != declaredLength.getAsInt()) {
            throw CompressionException.lengthMismatch(
                    configuration.compressionAlgorithm().id(), declaredLength.getAsInt(), decoded.length);
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
                CodecAttributes.VERSION_VALUE,
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
            CodecMetadataAttributeHandler.forOutbound(configuration, payloadBytes)
                    .applyTo(attributes);
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
