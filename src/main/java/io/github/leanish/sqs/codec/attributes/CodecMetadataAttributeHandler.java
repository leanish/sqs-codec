/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import io.github.leanish.sqs.codec.CodecConfiguration;
import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Parses and writes the single codec metadata attribute for SQS messages.
 */
public class CodecMetadataAttributeHandler {

    private static final Pattern VERSION_ONE_OR_MINOR = Pattern.compile("1(?:\\.(?:0|[1-9][0-9]*))?");

    private final CodecConfiguration configuration;
    @Nullable
    private final String checksumValue;
    // The raw payload length (l), -1 when absent: written exactly when the payload is compressed; on read, a compressed
    // payload may lack it (0.5.x writers could omit it), and it's ignored without compression.
    private final int rawLength;

    private CodecMetadataAttributeHandler(
            CodecConfiguration configuration,
            @Nullable String checksumValue,
            int rawLength) {
        this.configuration = configuration;
        this.checksumValue = checksumValue;
        this.rawLength = rawLength;
    }

    public static boolean hasCodecAttributes(Map<String, MessageAttributeValue> attributes) {
        return attributes.containsKey(CodecAttributes.META);
    }

    /**
     * The metadata for a fresh send: {@code l} is written exactly when the payload is compressed.
     *
     * @param configuration the codec configuration the payload is sent with
     * @param payloadBytes the raw payload, before compression and encoding
     * @return the metadata to apply to the message
     * @throws UnsupportedCodecMetadataException when the configuration's version isn't
     *         {@value CodecAttributes#VERSION_VALUE}, which readers would refuse, or it enables nothing
     */
    public static CodecMetadataAttributeHandler forOutbound(CodecConfiguration configuration, byte[] payloadBytes) {
        if (configuration.version() != CodecAttributes.VERSION_VALUE) {
            throw UnsupportedCodecMetadataException.unsupportedVersion(String.valueOf(configuration.version()));
        }
        CodecConfiguration effectiveConfiguration = effectiveConfiguration(configuration);
        validateNonNoOpMetadata(
                effectiveConfiguration.compressionAlgorithm(),
                effectiveConfiguration.encodingAlgorithm(),
                effectiveConfiguration.checksumAlgorithm());
        String checksumValue = checksumValue(effectiveConfiguration.checksumAlgorithm(), payloadBytes);
        int rawLength = effectiveConfiguration.compressionAlgorithm() == CompressionAlgorithm.NONE ? -1 : payloadBytes.length;
        return new CodecMetadataAttributeHandler(
                effectiveConfiguration,
                checksumValue,
                rawLength);
    }

    /**
     * {@link #forOutbound(CodecConfiguration, byte[])}, kept so code built against 0.5.x keeps working:
     * {@code includeRawPayloadLength} is ignored, and {@code l} is always written when compressed, which 0.7.0 will
     * require. In 0.5.x, {@code false} omitted it.
     *
     * @param configuration the codec configuration the payload is sent with
     * @param payloadBytes the raw payload, before compression and encoding
     * @param includeRawPayloadLength ignored
     * @return the metadata to apply to the message
     * @throws UnsupportedCodecMetadataException as {@link #forOutbound(CodecConfiguration, byte[])} does: when the
     *         configuration's version isn't {@value CodecAttributes#VERSION_VALUE} (0.5.x wrote any version), or it
     *         enables nothing
     * @deprecated {@code l} is written whenever the configuration compresses; use
     *             {@link #forOutbound(CodecConfiguration, byte[])}
     */
    @Deprecated(since = "0.6.0")
    // Not @InlineMe: inlining would drop the evaluation of the caller's flag expression.
    @SuppressWarnings("InlineMeSuggester")
    public static CodecMetadataAttributeHandler forOutbound(
            CodecConfiguration configuration,
            byte[] payloadBytes,
            boolean includeRawPayloadLength) {
        return forOutbound(configuration, payloadBytes);
    }

    public static CodecMetadataAttributeHandler fromAttributes(Map<String, MessageAttributeValue> attributes) {
        if (!hasCodecAttributes(attributes)) {
            throw new IllegalArgumentException("Missing x-codec-meta attribute; check hasCodecAttributes first");
        }

        String metadataValue = MessageAttributeUtils.attributeValue(attributes, CodecAttributes.META);
        if (metadataValue == null || metadataValue.isBlank()) {
            throw UnsupportedCodecMetadataException.malformed(String.valueOf(metadataValue));
        }
        return parseMetadata(metadataValue);
    }

    public CodecConfiguration configuration() {
        return configuration;
    }

    @Nullable
    public String checksumValue() {
        return checksumValue;
    }

    /**
     * The raw payload length the sender declared ({@code l}): possibly absent for a compressed payload (0.5.x writers
     * could omit it, and a malformed value reads as absent), and always absent without compression.
     *
     * @return the declared raw length, or empty
     */
    public OptionalInt rawLength() {
        return rawLength < 0 ? OptionalInt.empty() : OptionalInt.of(rawLength);
    }

    /**
     * Writes this metadata as {@code x-codec-meta}: only the keys this version knows, in its key order, and metadata read
     * as version {@code 1.y} as {@code v=1}, since neither the minor nor unknown keys are kept; the checksum value is
     * written as it was parsed. To forward a message, pass on its attributes as they are, as the interceptor does for an
     * already encoded message.
     *
     * @param attributes the message attributes to write into
     */
    public void applyTo(Map<String, MessageAttributeValue> attributes) {
        attributes.put(CodecAttributes.META, MessageAttributeUtils.stringAttribute(formatMetadataValue()));
    }

    private static CodecMetadataAttributeHandler parseMetadata(String metadataValue) {
        String trimmed = metadataValue.trim();
        if (trimmed.isEmpty()) {
            throw UnsupportedCodecMetadataException.malformed(metadataValue);
        }

        String[] parts = trimmed.split(";", -1);
        Map<String, String> values = new HashMap<>();
        for (String part : parts) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int separatorIndex = entry.indexOf('=');
            if (separatorIndex <= 0) {
                throw UnsupportedCodecMetadataException.malformed(metadataValue);
            }
            String key = entry.substring(0, separatorIndex).trim().toLowerCase(Locale.ROOT);
            String value = entry.substring(separatorIndex + 1).trim();
            validateMetadataEntry(metadataValue, key, value);
            if (values.putIfAbsent(key, value) != null) {
                throw UnsupportedCodecMetadataException.duplicateKey(key);
            }
        }

        int version = parseRequiredVersion(values);
        CompressionAlgorithm compressionAlgorithm = parseCompressionAlgorithm(values);
        EncodingAlgorithm encodingAlgorithm = parseEncodingAlgorithm(values);
        ChecksumAlgorithm checksumAlgorithm = parseChecksumAlgorithm(values);

        validateCompressionEncoding(metadataValue, compressionAlgorithm, encodingAlgorithm);
        validateNonNoOpMetadata(compressionAlgorithm, encodingAlgorithm, checksumAlgorithm);
        int rawLength = compressionAlgorithm == CompressionAlgorithm.NONE ? -1 : parseRawLength(values);
        String checksumValue = parseChecksumValue(values, checksumAlgorithm);
        CodecConfiguration configuration = new CodecConfiguration(
                version,
                compressionAlgorithm,
                encodingAlgorithm,
                checksumAlgorithm);
        return new CodecMetadataAttributeHandler(
                configuration,
                checksumValue,
                rawLength);
    }

    private static int parseRequiredVersion(Map<String, String> values) {
        String versionValue = values.get(CodecAttributes.META_VERSION_KEY);
        if (versionValue == null) {
            throw UnsupportedCodecMetadataException.missingKey(CodecAttributes.META_VERSION_KEY);
        }
        // "1" or "1.<minor>", exactly as written ("01", "+1" or "1.01" aren't). A minor only adds what an earlier minor's
        // reader can ignore, so every 1.y reads as 1; the minor is matched as text, never parsed, so no value overflows.
        if (VERSION_ONE_OR_MINOR.matcher(versionValue).matches()) {
            return CodecAttributes.VERSION_VALUE;
        }
        throw UnsupportedCodecMetadataException.unsupportedVersion(versionValue);
    }

    /** The raw length, as 0.5.x read it: missing, blank, negative or unparseable is absent. */
    private static int parseRawLength(Map<String, String> values) {
        String rawLengthValue = values.get(CodecAttributes.META_RAW_LENGTH_KEY);
        if (rawLengthValue == null || rawLengthValue.isBlank()) {
            return -1;
        }
        try {
            return Math.max(Integer.parseInt(rawLengthValue), -1);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Nullable
    private static String parseChecksumValue(Map<String, String> values, ChecksumAlgorithm checksumAlgorithm) {
        String checksumValue = values.get(CodecAttributes.META_CHECKSUM_VALUE_KEY);
        if (checksumAlgorithm == ChecksumAlgorithm.NONE) {
            if (checksumValue != null) {
                throw ChecksumValidationException.missingAlgorithm();
            }
            return null;
        }
        if (checksumValue == null || checksumValue.isBlank()) {
            throw ChecksumValidationException.missingAttribute(CodecAttributes.META_CHECKSUM_VALUE_KEY);
        }
        return checksumValue;
    }

    @Nullable
    private static String checksumValue(ChecksumAlgorithm checksumAlgorithm, byte[] payloadBytes) {
        if (checksumAlgorithm == ChecksumAlgorithm.NONE) {
            return null;
        }
        return checksumAlgorithm.implementation().checksum(payloadBytes);
    }

    private static void validateNonNoOpMetadata(
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm,
            ChecksumAlgorithm checksumAlgorithm) {
        if (compressionAlgorithm == CompressionAlgorithm.NONE
                && encodingAlgorithm == EncodingAlgorithm.NONE
                && checksumAlgorithm == ChecksumAlgorithm.NONE) {
            throw UnsupportedCodecMetadataException.noOp();
        }
    }

    private static CompressionAlgorithm parseCompressionAlgorithm(Map<String, String> values) {
        String compressionValue = values.get(CodecAttributes.META_COMPRESSION_KEY);
        if (compressionValue == null) {
            return CompressionAlgorithm.NONE;
        }
        return CompressionAlgorithm.fromId(compressionValue);
    }

    private static ChecksumAlgorithm parseChecksumAlgorithm(Map<String, String> values) {
        String checksumAlgorithmValue = values.get(CodecAttributes.META_CHECKSUM_ALGORITHM_KEY);
        if (checksumAlgorithmValue == null) {
            return ChecksumAlgorithm.NONE;
        }
        return ChecksumAlgorithm.fromId(checksumAlgorithmValue);
    }

    private static EncodingAlgorithm parseEncodingAlgorithm(Map<String, String> values) {
        String encodingValue = values.get(CodecAttributes.META_ENCODING_KEY);
        if (encodingValue == null) {
            throw UnsupportedCodecMetadataException.missingKey(CodecAttributes.META_ENCODING_KEY);
        }
        return EncodingAlgorithm.fromId(encodingValue);
    }

    private static void validateMetadataEntry(String metadataValue, String key, String value) {
        if (key.isEmpty() || hasBlankRequiredValue(key, value)) {
            throw UnsupportedCodecMetadataException.malformed(metadataValue);
        }
    }

    private static void validateCompressionEncoding(
            String metadataValue,
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm) {
        if (compressionAlgorithm != CompressionAlgorithm.NONE
                && encodingAlgorithm == EncodingAlgorithm.NONE) {
            throw UnsupportedCodecMetadataException.malformed(metadataValue);
        }
    }

    private static CodecConfiguration effectiveConfiguration(CodecConfiguration configuration) {
        EncodingAlgorithm effectiveEncoding = EncodingAlgorithm.effectiveFor(
                configuration.compressionAlgorithm(),
                configuration.encodingAlgorithm());
        if (effectiveEncoding == configuration.encodingAlgorithm()) {
            return configuration;
        }
        return new CodecConfiguration(
                configuration.version(),
                configuration.compressionAlgorithm(),
                effectiveEncoding,
                configuration.checksumAlgorithm());
    }

    private static boolean hasBlankRequiredValue(String key, String value) {
        return isKnownKey(key)
                && value.isEmpty()
                && !CodecAttributes.META_CHECKSUM_VALUE_KEY.equals(key)
                // Ignored without compression, and read as absent with it.
                && !CodecAttributes.META_RAW_LENGTH_KEY.equals(key);
    }

    private static boolean isKnownKey(String key) {
        return switch (key) {
            case CodecAttributes.META_VERSION_KEY,
                    CodecAttributes.META_COMPRESSION_KEY,
                    CodecAttributes.META_ENCODING_KEY,
                    CodecAttributes.META_CHECKSUM_ALGORITHM_KEY,
                    CodecAttributes.META_CHECKSUM_VALUE_KEY,
                    CodecAttributes.META_RAW_LENGTH_KEY -> true;
            default -> false;
        };
    }

    private String formatMetadataValue() {
        StringBuilder metadataValue = new StringBuilder()
                .append("v=").append(configuration.version())
                .append(";c=").append(configuration.compressionAlgorithm().id())
                .append(";e=").append(configuration.encodingAlgorithm().id())
                .append(";h=").append(configuration.checksumAlgorithm().id());
        if (checksumValue != null) {
            metadataValue.append(";s=").append(checksumValue);
        }
        if (configuration.compressionAlgorithm() != CompressionAlgorithm.NONE && rawLength >= 0) {
            metadataValue.append(";l=").append(rawLength);
        }
        return metadataValue.toString();
    }
}
