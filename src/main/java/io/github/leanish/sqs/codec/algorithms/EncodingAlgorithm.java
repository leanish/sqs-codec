/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.leanish.sqs.codec.algorithms.encoding.Base64Encoder;
import io.github.leanish.sqs.codec.algorithms.encoding.Encoder;
import io.github.leanish.sqs.codec.algorithms.encoding.NoOpEncoder;
import io.github.leanish.sqs.codec.algorithms.encoding.StandardBase64Encoder;

/**
 * Supported encoding algorithms and their encoder implementations.
 */
public enum EncodingAlgorithm {
    /** URL-safe Base64 for attribute values that might travel through URL contexts. */
    BASE64("base64", new Base64Encoder()),
    /** Standard Base64 for systems that require "+" "/" and "=" padding. */
    BASE64_STD("base64-std", new StandardBase64Encoder()),
    /** No encoding; payload is treated as UTF-8 bytes. */
    NONE("none", new NoOpEncoder());

    private static final Map<String, EncodingAlgorithm> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    algorithm -> algorithm.id.toLowerCase(Locale.ROOT),
                    algorithm -> algorithm));

    private final String id;
    private final Encoder implementation;

    EncodingAlgorithm(String id, Encoder implementation) {
        this.id = id;
        this.implementation = implementation;
    }

    public String id() {
        return id;
    }

    public Encoder implementation() {
        return implementation;
    }

    public static EncodingAlgorithm fromId(String value) {
        if (value.isBlank()) {
            throw UnsupportedAlgorithmException.encoding(value);
        }
        EncodingAlgorithm encoding = BY_ID.get(value.toLowerCase(Locale.ROOT));
        if (encoding == null) {
            throw UnsupportedAlgorithmException.encoding(value);
        }
        return encoding;
    }

    public static EncodingAlgorithm effectiveFor(
            CompressionAlgorithm compressionAlgorithm,
            EncodingAlgorithm encodingAlgorithm) {
        if (encodingAlgorithm == EncodingAlgorithm.NONE
                && compressionAlgorithm != CompressionAlgorithm.NONE) {
            return EncodingAlgorithm.BASE64;
        }
        return encodingAlgorithm;
    }
}
