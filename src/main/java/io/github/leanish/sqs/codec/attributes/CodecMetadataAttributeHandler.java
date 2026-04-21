/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    private final CodecConfiguration configuration;
    @Nullable
    private final String checksumValue;
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

    public static CodecMetadataAttributeHandler forOutbound(
            CodecConfiguration configuration,
            byte[] payloadBytes,
            boolean includeRawPayloadLength) {
        CodecConfiguration effectiveConfiguration = effectiveConfiguration(configuration);
        validateNonNoOpMetadata(
                effectiveConfiguration.compressionAlgorithm(),
                effectiveConfiguration.encodingAlgorithm(),
                effectiveConfiguration.checksumAlgorithm());
        String checksumValue = checksumValue(effectiveConfiguration.checksumAlgorithm(), payloadBytes);
        int rawLength = includeRawPayloadLength ? payloadBytes.length : -1;
        return new CodecMetadataAttributeHandler(
                effectiveConfiguration,
                checksumValue,
                rawLength);
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
        int rawLength = parseRawLength(values);
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
        try {
            int parsedVersion = Integer.parseInt(versionValue);
            if (parsedVersion != CodecAttributes.VERSION_VALUE) {
                throw UnsupportedCodecMetadataException.unsupportedVersion(versionValue);
            }
            return parsedVersion;
        } catch (NumberFormatException e) {
            throw UnsupportedCodecMetadataException.unsupportedVersion(versionValue);
        }
    }

    private static int parseRawLength(Map<String, String> values) {
        String rawLengthValue = values.get(CodecAttributes.META_RAW_LENGTH_KEY);
        if (rawLengthValue == null || rawLengthValue.isBlank()) {
            return -1;
        }
        try {
            int parsedRawLength = Integer.parseInt(rawLengthValue);
            return parsedRawLength < 0 ? -1 : parsedRawLength;
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
        if (configuration.compressionAlgorithm() != CompressionAlgorithm.NONE
                && rawLength >= 0) {
            metadataValue.append(";l=").append(rawLength);
        }
        return metadataValue.toString();
    }
}
