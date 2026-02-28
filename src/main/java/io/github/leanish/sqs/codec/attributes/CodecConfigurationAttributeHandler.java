/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.leanish.sqs.codec.CodecConfiguration;
import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

/**
 * Parses and writes codec configuration attributes for SQS messages.
 */
public class CodecConfigurationAttributeHandler {

    private final CodecConfiguration configuration;

    private CodecConfigurationAttributeHandler(
            CodecConfiguration configuration) {
        this.configuration = configuration;
    }

    public static boolean hasCodecAttributes(Map<String, MessageAttributeValue> attributes) {
        return attributes.containsKey(CodecAttributes.CONF);
    }

    public static CodecConfigurationAttributeHandler forOutbound(CodecConfiguration configuration) {
        EncodingAlgorithm effectiveEncoding = EncodingAlgorithm.effectiveFor(
                configuration.compressionAlgorithm(),
                configuration.encodingAlgorithm());
        CodecConfiguration effectiveConfiguration = new CodecConfiguration(
                configuration.version(),
                configuration.compressionAlgorithm(),
                effectiveEncoding,
                configuration.checksumAlgorithm());
        return new CodecConfigurationAttributeHandler(
                effectiveConfiguration);
    }

    public static CodecConfigurationAttributeHandler fromAttributes(Map<String, MessageAttributeValue> attributes) {
        String confValue = MessageAttributeUtils.attributeValue(attributes, CodecAttributes.CONF);
        if (attributes.containsKey(CodecAttributes.CONF)) {
            if (confValue == null || confValue.isBlank()) {
                throw UnsupportedCodecConfigurationException.malformed(String.valueOf(confValue));
            }
            CodecConfiguration configuration = parseConf(confValue);
            return new CodecConfigurationAttributeHandler(configuration);
        }
        CodecConfiguration configuration = new CodecConfiguration(
                CodecAttributes.VERSION_VALUE,
                CompressionAlgorithm.NONE,
                EncodingAlgorithm.NONE,
                ChecksumAlgorithm.NONE);
        return new CodecConfigurationAttributeHandler(configuration);
    }

    public CodecConfiguration configuration() {
        return configuration;
    }

    public void applyTo(Map<String, MessageAttributeValue> attributes) {
        attributes.put(CodecAttributes.CONF,
                MessageAttributeUtils.stringAttribute(formatConfValue(configuration)));
    }

    private static CodecConfiguration parseConf(String confValue) {
        String trimmed = confValue.trim();
        if (trimmed.isEmpty()) {
            throw UnsupportedCodecConfigurationException.malformed(confValue);
        }

        int version = CodecAttributes.VERSION_VALUE;
        CompressionAlgorithm compressionAlgorithm = CompressionAlgorithm.NONE;
        EncodingAlgorithm encodingAlgorithm = EncodingAlgorithm.NONE;
        ChecksumAlgorithm checksumAlgorithm = ChecksumAlgorithm.NONE;

        String[] parts = trimmed.split(";");
        Map<String, String> values = new HashMap<>();
        for (String part : parts) {
            String entry = part.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int idx = entry.indexOf('=');
            if (idx <= 0 || idx == entry.length() - 1) {
                throw UnsupportedCodecConfigurationException.malformed(confValue);
            }
            String key = entry.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = entry.substring(idx + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                throw UnsupportedCodecConfigurationException.malformed(confValue);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw UnsupportedCodecConfigurationException.duplicateKey(key);
            }
        }

        String versionValue = values.get("v");
        if (versionValue != null) {
            try {
                version = Integer.parseInt(versionValue);
            } catch (NumberFormatException e) {
                throw UnsupportedCodecConfigurationException.unsupportedVersion(versionValue);
            }
            if (version != CodecAttributes.VERSION_VALUE) {
                throw UnsupportedCodecConfigurationException.unsupportedVersion(versionValue);
            }
        }

        String compressionValue = values.get("c");
        if (compressionValue != null) {
            compressionAlgorithm = CompressionAlgorithm.fromId(compressionValue);
        }
        String encodingValue = values.get("e");
        if (encodingValue != null) {
            encodingAlgorithm = EncodingAlgorithm.fromId(encodingValue);
        }
        String checksumValue = values.get("h");
        if (checksumValue != null) {
            checksumAlgorithm = ChecksumAlgorithm.fromId(checksumValue);
        }

        return new CodecConfiguration(version, compressionAlgorithm, encodingAlgorithm, checksumAlgorithm);
    }

    private static String formatConfValue(CodecConfiguration configuration) {
        return "v=" + configuration.version()
                + ";c=" + configuration.compressionAlgorithm().id()
                + ";e=" + configuration.encodingAlgorithm().id()
                + ";h=" + configuration.checksumAlgorithm().id();
    }
}
