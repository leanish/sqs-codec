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

    public static CodecMetadataAttributeHandler forOutbound(CodecConfiguration configuration, byte[] payloadBytes) {
        String checksumValue = null;
        if (configuration.checksumAlgorithm() != ChecksumAlgorithm.NONE) {
            checksumValue = configuration.checksumAlgorithm()
                    .implementation()
                    .checksum(payloadBytes);
        }
        return new CodecMetadataAttributeHandler(
                configuration,
                checksumValue,
                payloadBytes.length);
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

        int version = CodecAttributes.VERSION_VALUE;
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
                    || CodecAttributes.META_CHECKSUM_ALGORITHM_KEY.equals(key)
                    || CodecAttributes.META_CHECKSUM_VALUE_KEY.equals(key)
                    || CodecAttributes.META_RAW_LENGTH_KEY.equals(key);
            if (key.isEmpty() || (knownKey
                    && value.isEmpty()
                    && !CodecAttributes.META_CHECKSUM_VALUE_KEY.equals(key)
                    && !CodecAttributes.META_RAW_LENGTH_KEY.equals(key))) {
                throw UnsupportedCodecMetadataException.malformed(metadataValue);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw UnsupportedCodecMetadataException.duplicateKey(key);
            }
        }

        // Unknown keys are intentionally ignored for forward compatibility.
        String versionValue = values.get(CodecAttributes.META_VERSION_KEY);
        if (versionValue != null) {
            try {
                version = Integer.parseInt(versionValue);
            } catch (NumberFormatException e) {
                throw UnsupportedCodecMetadataException.unsupportedVersion(versionValue);
            }
            if (version != CodecAttributes.VERSION_VALUE) {
                throw UnsupportedCodecMetadataException.unsupportedVersion(versionValue);
            }
        }

        String compressionValue = values.get(CodecAttributes.META_COMPRESSION_KEY);
        if (compressionValue != null) {
            compressionAlgorithm = CompressionAlgorithm.fromId(compressionValue);
        }
        String checksumAlgorithmValue = values.get(CodecAttributes.META_CHECKSUM_ALGORITHM_KEY);
        if (checksumAlgorithmValue != null) {
            checksumAlgorithm = ChecksumAlgorithm.fromId(checksumAlgorithmValue);
        }
        String checksumValue = values.get(CodecAttributes.META_CHECKSUM_VALUE_KEY);
        if (checksumAlgorithmValue == null && checksumValue != null && !checksumValue.isBlank()) {
            checksumAlgorithm = ChecksumAlgorithm.MD5;
        }

        int rawLength = parseRawLength(values);
        checksumValue = parseChecksumValue(values, checksumAlgorithm);
        CodecConfiguration configuration = new CodecConfiguration(
                version,
                compressionAlgorithm,
                checksumAlgorithm);
        return new CodecMetadataAttributeHandler(
                configuration,
                checksumValue,
                rawLength);
    }

    private static int parseRawLength(Map<String, String> values) {
        String rawLengthValue = values.get(CodecAttributes.META_RAW_LENGTH_KEY);
        if (rawLengthValue == null || rawLengthValue.isBlank()) {
            return 0;
        }
        try {
            int parsedRawLength = Integer.parseInt(rawLengthValue);
            if (parsedRawLength < 0) {
                return 0;
            }
            return parsedRawLength;
        } catch (NumberFormatException e) {
            return 0;
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

    private String formatMetadataValue() {
        String metadataValue = "v=" + configuration.version()
                + ";c=" + configuration.compressionAlgorithm().id()
                + ";h=" + configuration.checksumAlgorithm().id();
        if (checksumValue == null) {
            return metadataValue + ";l=" + rawLength;
        }
        return metadataValue + ";s=" + checksumValue + ";l=" + rawLength;
    }
}
