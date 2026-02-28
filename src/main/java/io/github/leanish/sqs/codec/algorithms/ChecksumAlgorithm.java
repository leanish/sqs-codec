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

import io.github.leanish.sqs.codec.algorithms.checksum.Digestor;
import io.github.leanish.sqs.codec.algorithms.checksum.Md5Digestor;
import io.github.leanish.sqs.codec.algorithms.checksum.Sha256Digestor;
import io.github.leanish.sqs.codec.algorithms.checksum.UndigestedDigestor;

/**
 * Supported checksum algorithms and their digestor implementations.
 */
public enum ChecksumAlgorithm {
    /** MD5 checksum for lightweight integrity checks. */
    MD5("md5", new Md5Digestor()),
    /** SHA-256 checksum for stronger integrity guarantees. */
    SHA256("sha256", new Sha256Digestor()),
    /** No checksum; integrity attributes are omitted. */
    NONE("none", new UndigestedDigestor());

    private static final Map<String, ChecksumAlgorithm> BY_ID = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(
                    algorithm -> algorithm.id.toLowerCase(Locale.ROOT),
                    algorithm -> algorithm));

    private final String id;
    private final Digestor implementation;

    ChecksumAlgorithm(String id, Digestor implementation) {
        this.id = id;
        this.implementation = implementation;
    }

    public String id() {
        return id;
    }

    public Digestor implementation() {
        return implementation;
    }

    public static ChecksumAlgorithm fromId(String value) {
        if (value.isBlank()) {
            throw UnsupportedAlgorithmException.checksum(value);
        }
        ChecksumAlgorithm algorithm = BY_ID.get(value.toLowerCase(Locale.ROOT));
        if (algorithm == null) {
            throw UnsupportedAlgorithmException.checksum(value);
        }
        return algorithm;
    }
}
