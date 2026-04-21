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
        String checksumValue = null;
        if (effectiveConfiguration.checksumAlgorithm() != ChecksumAlgorithm.NONE) {
            checksumValue = effectiveConfiguration.checksumAlgorithm()
                    .implementation()
                    .checksum(payloadBytes);
        }
        int rawLength = includeRawPayloadLength ? payloadBytes.length : -1;
        return new CodecMetadataAttributeHandler(
                effectiveConfiguration,
                checksumValue,
                rawLength);
    }

    public static CodecMetadataAttributeHandler fromAttributes(Map<String, MessageAttributeValue> attributes) {
        if (!attributes.containsKey(CodecAttributes.META)) {
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

        CompressionAlgorithm compressionAlgorithm = CompressionAlgorithm.NONE;
        ChecksumAlgorithm checksumAlgorithm = ChecksumAlgorithm.NONE;

        String[] parts = trimmed.split(";", -1);
        Map<String, String> values = new HashMap<>();
        for (String part : parts) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int idx = entry.indexOf('=');
            if (idx <= 0) {
                throw UnsupportedCodecMetadataException.malformed(metadataValue);
            }
            String key = entry.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = entry.substring(idx + 1).trim();
            boolean knownKey = CodecAttributes.META_VERSION_KEY.equals(key)
                    || CodecAttributes.META_COMPRESSION_KEY.equals(key)
                    || CodecAttributes.META_ENCODING_KEY.equals(key)
                    || CodecAttributes.META_CHECKSUM_ALGORITHM_KEY.equals(key)
                    || CodecAttributes.META_CHECKSUM_VALUE_KEY.equals(key)
                    || CodecAttributes.META_RAW_LENGTH_KEY.equals(key);
            if (key.isEmpty() || hasBlankRequiredValue(key, value, knownKey)) {
                throw UnsupportedCodecMetadataException.malformed(metadataValue);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw UnsupportedCodecMetadataException.duplicateKey(key);
            }
        }

        int version = parseRequiredVersion(values);

        String compressionValue = values.get(CodecAttributes.META_COMPRESSION_KEY);
        if (compressionValue != null) {
            compressionAlgorithm = CompressionAlgorithm.fromId(compressionValue);
        }
        String encodingValue = values.get(CodecAttributes.META_ENCODING_KEY);
        boolean hasExplicitEncoding = encodingValue != null;
        EncodingAlgorithm encodingAlgorithm = EncodingAlgorithm.NONE;
        if (encodingValue != null) {
            encodingAlgorithm = EncodingAlgorithm.fromId(encodingValue);
        }
        String checksumAlgorithmValue = values.get(CodecAttributes.META_CHECKSUM_ALGORITHM_KEY);
        if (checksumAlgorithmValue != null) {
            checksumAlgorithm = ChecksumAlgorithm.fromId(checksumAlgorithmValue);
        }

        validateCompressionEncoding(metadataValue, compressionAlgorithm, encodingAlgorithm, hasExplicitEncoding);
        validateNonNoOpMetadata(compressionAlgorithm, encodingAlgorithm, checksumAlgorithm);
        int rawLength = parseRawLength(values);
        String checksumValue = parseChecksumValue(values, checksumAlgorithm);
        CodecConfiguration configuration = effectiveConfiguration(new CodecConfiguration(
                version,
                compressionAlgorithm,
                encodingAlgorithm,
                checksumAlgorithm));
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
            if (parsedRawLength < 0) {
                return -1;
            }
            return parsedRawLength;
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

    private static void validateCompressionEncoding(
            String metadataValue,
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm,
            boolean hasExplicitEncoding) {
        if (compressionAlgorithm != CompressionAlgorithm.NONE
                && encodingAlgorithm == EncodingAlgorithm.NONE
                && hasExplicitEncoding) {
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

    private static boolean hasBlankRequiredValue(String key, String value, boolean knownKey) {
        return knownKey
                && value.isEmpty()
                && !CodecAttributes.META_CHECKSUM_VALUE_KEY.equals(key)
                && !CodecAttributes.META_RAW_LENGTH_KEY.equals(key);
    }

    private String formatMetadataValue() {
        String metadataValue = "v=" + configuration.version()
                + ";c=" + configuration.compressionAlgorithm().id()
                + ";e=" + configuration.encodingAlgorithm().id()
                + ";h=" + configuration.checksumAlgorithm().id();
        if (checksumValue != null) {
            metadataValue += ";s=" + checksumValue;
        }
        if (configuration.compressionAlgorithm() != CompressionAlgorithm.NONE
                && rawLength >= 0) {
            metadataValue += ";l=" + rawLength;
        }
        return metadataValue;
    }
}
