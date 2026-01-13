/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import io.github.leanish.sqs.codec.PayloadCodecException;

/**
 * Thrown when codec configuration attributes are malformed or unsupported.
 */
public class UnsupportedCodecConfigurationException extends PayloadCodecException {
    public UnsupportedCodecConfigurationException(String message) {
        super(message);
    }

    public static UnsupportedCodecConfigurationException malformed(String configuration) {
        return new UnsupportedCodecConfigurationException(
                "Unsupported codec configuration: " + configuration);
    }

    public static UnsupportedCodecConfigurationException duplicateKey(String key) {
        return new UnsupportedCodecConfigurationException(
                "Duplicate codec configuration key: " + key);
    }

    public static UnsupportedCodecConfigurationException unsupportedVersion(String version) {
        return new UnsupportedCodecConfigurationException(
                "Unsupported codec version: " + version);
    }
}
