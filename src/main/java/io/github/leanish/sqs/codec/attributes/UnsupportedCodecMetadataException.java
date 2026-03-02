/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.attributes;

import io.github.leanish.sqs.codec.CodecException;

/**
 * Thrown when codec metadata is malformed or unsupported.
 */
public class UnsupportedCodecMetadataException extends CodecException {
    public UnsupportedCodecMetadataException(String message) {
        super(message);
    }

    public static UnsupportedCodecMetadataException malformed(String metadata) {
        return new UnsupportedCodecMetadataException(
                "Unsupported codec metadata: " + metadata);
    }

    public static UnsupportedCodecMetadataException duplicateKey(String key) {
        return new UnsupportedCodecMetadataException(
                "Duplicate codec metadata key: " + key);
    }

    public static UnsupportedCodecMetadataException unsupportedVersion(String version) {
        return new UnsupportedCodecMetadataException(
                "Unsupported codec version: " + version);
    }
}
