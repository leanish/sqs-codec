/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec;

import io.github.leanish.sqs.codec.algorithms.ChecksumAlgorithm;
import io.github.leanish.sqs.codec.algorithms.CompressionAlgorithm;
import io.github.leanish.sqs.codec.algorithms.EncodingAlgorithm;

/**
 * Immutable configuration for codec version, compression, encoding and checksum settings.
 *
 * @param version the metadata's major version: metadata read as {@code 1.y} has version {@code 1}
 * @param compressionAlgorithm the compression applied to the payload
 * @param encodingAlgorithm the encoding applied after compression
 * @param checksumAlgorithm the checksum computed over the raw payload
 */
public record CodecConfiguration(
        int version,
        CompressionAlgorithm compressionAlgorithm,
        EncodingAlgorithm encodingAlgorithm,
        ChecksumAlgorithm checksumAlgorithm) {
}
