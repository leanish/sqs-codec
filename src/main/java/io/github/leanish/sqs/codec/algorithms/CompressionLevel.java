/*
 * Copyright (c) 2026 Leandro Aguiar
 * Licensed under the MIT License.
 * See LICENSE file in the project root for full license information.
 */
package io.github.leanish.sqs.codec.algorithms;

/**
 * Relative compression effort for algorithms that support configurable levels.
 *
 * <p>{@link CompressionAlgorithm#GZIP} and {@link CompressionAlgorithm#ZSTD} map these values to
 * algorithm-specific settings. {@link CompressionAlgorithm#SNAPPY} and
 * {@link CompressionAlgorithm#NONE} ignore this setting.
 */
public enum CompressionLevel {
    /** Use the minimum supported compression effort. */
    MINIMUM,
    /** Use low compression effort. */
    LOW,
    /** Use the library default compression effort. */
    MEDIUM,
    /** Use high compression effort. */
    HIGH,
    /** Use the maximum supported compression effort. */
    MAXIMUM
}
