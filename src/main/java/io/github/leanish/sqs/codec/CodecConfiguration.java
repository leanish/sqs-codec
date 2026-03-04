/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;

/**
 * Immutable configuration for codec version, compression and checksum settings.
 */
public record CodecConfiguration(
        int version,
        CompressionAlgorithm compressionAlgorithm,
        ChecksumAlgorithm checksumAlgorithm) {
}
